"""B4-2 动态流水线引擎：按 spec 建图，支持任意增删角色/调整顺序/审查返工/人审门。

流水线 = 有序步骤列表（线性 SOP）。每步：
  key: 唯一标识（同时作为 role_models / role_prompts 的键）
  name: 展示名
  kind: analysis(产文档) | code(产代码文件) | test(写测试并执行) | review(判 PASS/FAIL 可返工)
  gate: 是否在该步之后插入人审门
  rework_target: review 步失败时回退到的步 key（默认上一个 code 步）
  system_prompt: 自定义系统提示词（缺省用内置默认）

未自定义时用 DEFAULT_PIPELINE，等价于原五角色硬编码图。
"""
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.graph import END, START, StateGraph
from langgraph.types import interrupt

from ..config import settings
from ..state import GraphState, parse_code_blocks
from ..workspace import task_dir, write_files
from .prompts import DEFAULT_PROMPTS
from .nodes import with_knowledge

DEFAULT_PIPELINE = {
    "steps": [
        {"key": "pm", "name": "产品经理", "kind": "analysis", "gate": True},
        {"key": "architect", "name": "架构师", "kind": "analysis", "gate": True},
        {"key": "coder", "name": "开发工程师", "kind": "code", "gate": False},
        {"key": "tester", "name": "测试工程师", "kind": "test", "gate": False},
        {"key": "reviewer", "name": "代码审查员", "kind": "review", "gate": False,
         "rework_target": "coder"},
    ],
    "final_gate": True,
}


def _prompt(step: dict, state: GraphState) -> str:
    key = step["key"]
    override = (state.get("role_prompts") or {}).get(key) or step.get("system_prompt")
    return override or DEFAULT_PROMPTS.get(key, f"你是{step.get('name', key)}，请完成你的职责。")


def _call(step: dict, llm_factory, state: GraphState, user_content: str) -> tuple[str, dict]:
    llm = llm_factory(step["key"], state)
    resp = llm.invoke([SystemMessage(content=with_knowledge(_prompt(step, state), state)),
                       HumanMessage(content=user_content)])
    usage = getattr(resp, "usage_metadata", None) or {}
    tokens = {
        "input_tokens": state.get("input_tokens", 0) + (usage.get("input_tokens") or 0),
        "output_tokens": state.get("output_tokens", 0) + (usage.get("output_tokens") or 0),
    }
    return resp.content, tokens


def _upsert(docs: list, key: str, name: str, content: str) -> list:
    """追加或更新指定 key 的产出文档（同 key 覆盖为最新）。"""
    docs = [d for d in (docs or []) if d["key"] != key]
    docs.append({"key": key, "name": name, "content": content})
    return docs


def _context(state: GraphState) -> str:
    parts = [f"用户需求：{state['requirement']}"]
    docs = state.get("documents") or []
    if docs:
        parts.append("已有产出：\n" + "\n\n".join(f"## {d['name']}\n{d['content']}" for d in docs))
    if state.get("iterate_feedback"):
        parts.append(f"用户迭代反馈（在现有产物上修改）：{state['iterate_feedback']}")
    if state.get("review_comments"):
        parts.append(f"上一轮审查意见（必须修复）：{state['review_comments']}")
    if state.get("human_feedback"):
        parts.append(f"人审驳回意见（必须修复）：{state['human_feedback']}")
    return "\n\n".join(parts)


def make_role_node(step: dict, llm_factory, test_runner):
    key, name, kind = step["key"], step.get("name", step["key"]), step.get("kind", "analysis")

    def node(state: GraphState) -> dict:
        reply, tokens = _call(step, llm_factory, state, _context(state))
        out = {**tokens, "human_feedback": "", "reject_target": "", "iterate_feedback": ""}
        if kind == "code":
            files = parse_code_blocks(reply)
            out["code_files"] = files
            doc = f"生成了 {len(files)} 个代码文件"
        elif kind == "test":
            test_files = parse_code_blocks(reply)
            write_files(state["task_id"], (state.get("code_files") or []) + test_files)
            code, output = test_runner(task_dir(state["task_id"]))
            doc = f"exit_code={code}\n{output}"
            out["test_report"] = doc
        elif kind == "review":
            passed = reply.strip().upper().startswith("PASS")
            out["review_passed"] = passed
            out["review_comments"] = "" if passed else reply
            if not passed:
                out["iteration_count"] = state.get("iteration_count", 0) + 1
            doc = reply
        else:
            doc = reply
        out["documents"] = _upsert(state.get("documents"), key, name, doc)
        return out
    return node


def make_gate(step_key: str, name: str):
    def gate(state: GraphState) -> dict:
        if state.get("auto_mode"):
            return {"human_feedback": "", "reject_target": ""}
        decision = interrupt({"gate": f"{step_key}_gate", "question": f"请确认「{name}」的产出",
                              "payload": {}})
        if decision.get("decision") == "reject":
            return {"human_feedback": decision.get("comment", "驳回"),
                    "reject_target": decision.get("target") or ""}
        return {"human_feedback": "", "reject_target": ""}
    return gate


def build_pipeline_graph(spec: dict, llm_factory, checkpointer, test_runner):
    """按 spec 动态建图。"""
    steps = spec["steps"]
    final_gate = spec.get("final_gate", True)
    keys = [s["key"] for s in steps]
    code_entry = next((s["key"] for s in steps if s.get("kind") == "code"), keys[0])

    g = StateGraph(GraphState)
    for step in steps:
        g.add_node(step["key"], make_role_node(step, llm_factory, test_runner))
        if step.get("gate"):
            g.add_node(f"{step['key']}__gate", make_gate(step["key"], step.get("name", step["key"])))
    if final_gate:
        g.add_node("accept_gate", make_gate("accept", "最终验收"))

    def enter_after(i: int) -> str:
        """第 i 步完成后进入的节点名。"""
        if i + 1 < len(steps):
            return steps[i + 1]["key"]
        return "accept_gate" if final_gate else END

    for i, step in enumerate(steps):
        key, kind = step["key"], step.get("kind", "analysis")
        gate_node = f"{key}__gate" if step.get("gate") else None
        nxt = enter_after(i)

        if kind == "review":
            target = step.get("rework_target", code_entry)

            def _after_review(s: GraphState, _nxt=nxt, _gate=gate_node, _target=target) -> str:
                if s.get("review_passed") or s.get("iteration_count", 0) >= settings.max_fix_rounds:
                    return _gate or _nxt
                return _target
            forward = _gate_or(gate_node, nxt)
            g.add_conditional_edges(key, _after_review,
                                    {target: target, _label(forward): forward})
        elif gate_node:
            g.add_edge(key, gate_node)
        else:
            g.add_edge(key, nxt)

        if gate_node:
            def _after_gate(s: GraphState, _nxt=nxt, _key=key) -> str:
                if s.get("human_feedback"):
                    t = s.get("reject_target")
                    return t if t in keys else _key
                return _nxt
            targets = {k: k for k in keys}
            targets[_label(nxt)] = nxt
            g.add_conditional_edges(gate_node, _after_gate, targets)

    if final_gate:
        def _after_accept(s: GraphState) -> str:
            if s.get("human_feedback"):
                t = s.get("reject_target")
                return t if t in keys else code_entry
            return END
        targets = {k: k for k in keys}
        targets[_label(END)] = END
        g.add_conditional_edges("accept_gate", _after_accept, targets)

    # 入口：迭代反馈直达 code 入口，否则第一步
    g.add_conditional_edges(
        START,
        lambda s: code_entry if s.get("iterate_feedback") else keys[0],
        {code_entry: code_entry, keys[0]: keys[0]},
    )
    return g.compile(checkpointer=checkpointer)


def _label(target) -> str:
    """conditional_edges 路由字典的键名（END 用固定标签）。"""
    return "__end__" if target == END else target


def _gate_or(gate_node: str | None, nxt):
    return gate_node or nxt
