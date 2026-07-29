from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.types import interrupt

from ..state import GraphState, parse_code_blocks
from ..workspace import task_dir, write_files
from .prompts import DEFAULT_PROMPTS


def _prompt(state: GraphState, role: str) -> str:
    return (state.get("role_prompts") or {}).get(role, DEFAULT_PROMPTS[role])


def _call(llm_factory, role: str, state: GraphState, user_content: str) -> tuple[str, dict]:
    """调用模型，返回 (内容, 全图累计token字段)。无 usage 元数据时累计值不变。"""
    llm = llm_factory(role, state)
    resp = llm.invoke([SystemMessage(content=_prompt(state, role)),
                       HumanMessage(content=user_content)])
    usage = getattr(resp, "usage_metadata", None) or {}
    tokens = {
        "input_tokens": state.get("input_tokens", 0) + (usage.get("input_tokens") or 0),
        "output_tokens": state.get("output_tokens", 0) + (usage.get("output_tokens") or 0),
    }
    return resp.content, tokens


def make_pm_node(llm_factory):
    def pm(state: GraphState) -> dict:
        content = f"用户需求：{state['requirement']}"
        if state.get("human_feedback"):
            content += f"\n\n人审驳回意见：{state['human_feedback']}"
        reply, tokens = _call(llm_factory, "pm", state, content)
        return {"prd": reply, "human_feedback": "", "reject_target": "", **tokens}
    return pm


def make_architect_node(llm_factory):
    def architect(state: GraphState) -> dict:
        content = f"PRD：\n{state['prd']}"
        if state.get("human_feedback"):
            content += f"\n\n人审驳回意见：{state['human_feedback']}"
        reply, tokens = _call(llm_factory, "architect", state, content)
        return {"design_doc": reply, "human_feedback": "", "reject_target": "", **tokens}
    return architect


def make_coder_node(llm_factory):
    def coder(state: GraphState) -> dict:
        if state.get("iterate_feedback"):
            # 多轮迭代：基于现有代码增量修改
            code_text = "\n\n".join(f"# {f['path']}\n{f['content']}"
                                    for f in state.get("code_files", []))
            content = (f"现有代码：\n{code_text}\n\n"
                       f"用户新反馈（请在现有代码基础上增量修改，输出全部文件）："
                       f"{state['iterate_feedback']}")
        else:
            content = f"设计文档：\n{state['design_doc']}"
            if state.get("review_comments"):
                content += f"\n\n上一轮审查意见（必须修复）：{state['review_comments']}"
            if state.get("human_feedback"):
                content += f"\n\n终审驳回意见（必须修复）：{state['human_feedback']}"
        reply, tokens = _call(llm_factory, "coder", state, content)
        return {"code_files": parse_code_blocks(reply), "iterate_feedback": "",
                "human_feedback": "", "reject_target": "", **tokens}
    return coder


def make_tester_node(llm_factory, test_runner):
    def tester(state: GraphState) -> dict:
        code_text = "\n\n".join(f"# {f['path']}\n{f['content']}" for f in state["code_files"])
        reply, tokens = _call(llm_factory, "tester", state, f"待测代码：\n{code_text}")
        test_files = parse_code_blocks(reply)
        write_files(state["task_id"], state["code_files"] + test_files)
        code, output = test_runner(task_dir(state["task_id"]))
        report = f"exit_code={code}\n{output}"
        return {"test_report": report, **tokens}
    return tester


def make_reviewer_node(llm_factory):
    def reviewer(state: GraphState) -> dict:
        code_text = "\n\n".join(f"# {f['path']}\n{f['content']}" for f in state["code_files"])
        reply, tokens = _call(llm_factory, "reviewer", state,
                              f"代码：\n{code_text}\n\n测试报告：\n{state['test_report']}")
        passed = reply.strip().upper().startswith("PASS")
        out = {"review_passed": passed, "review_comments": reply, **tokens}
        if not passed:
            out["iteration_count"] = state.get("iteration_count", 0) + 1
        return out
    return reviewer


def make_human_gate(gate_name: str, question: str):
    """人审门节点：auto_mode 直接放行；否则 interrupt 等待 resume（可带定向回退 target）。"""
    def gate(state: GraphState) -> dict:
        if state.get("auto_mode"):
            return {"human_feedback": "", "reject_target": ""}
        decision = interrupt({"gate": gate_name, "question": question,
                              "payload": {"prd": state.get("prd", ""),
                                          "design_doc": state.get("design_doc", "")}})
        if decision.get("decision") == "reject":
            return {"human_feedback": decision.get("comment", "驳回"),
                    "reject_target": decision.get("target") or ""}
        return {"human_feedback": "", "reject_target": ""}
    return gate
