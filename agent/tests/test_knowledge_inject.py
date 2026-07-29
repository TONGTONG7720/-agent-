"""B4-3b: 知识库参考注入到 Agent 上下文（两套引擎的 SystemMessage）。"""
from langchain_core.messages import AIMessage

from app.graph import nodes as legacy_nodes
from app.graph import pipeline as pl


class CapturingLLM:
    """记录收到的 SystemMessage 内容，返回固定回复。"""
    def __init__(self, reply="ok"):
        self.reply = reply
        self.system_seen = None

    def invoke(self, messages):
        self.system_seen = messages[0].content
        return AIMessage(content=self.reply)


def _factory(llm):
    def factory(role, state):
        return llm
    return factory


def test_pipeline_call_injects_knowledge():
    llm = CapturingLLM("PRD")
    step = {"key": "pm", "name": "产品", "kind": "analysis"}
    state = {"requirement": "做计算器", "knowledge": "团队规范：函数必须写类型注解"}
    reply, _ = pl._call(step, _factory(llm), state, "用户需求：做计算器")
    assert reply == "PRD"
    assert "团队规范：函数必须写类型注解" in llm.system_seen
    assert "参考" in llm.system_seen          # 有明确的参考段标识


def test_pipeline_call_without_knowledge_no_reference_block():
    llm = CapturingLLM("PRD")
    step = {"key": "pm", "name": "产品", "kind": "analysis"}
    reply, _ = pl._call(step, _factory(llm), {"requirement": "x"}, "用户需求：x")
    assert "参考知识库" not in (llm.system_seen or "")


def test_legacy_call_injects_knowledge():
    llm = CapturingLLM("PRD")
    state = {"requirement": "做计算器", "knowledge": "禁止使用 eval"}
    reply, _ = legacy_nodes._call(_factory(llm), "pm", state, "用户需求：做计算器")
    assert "禁止使用 eval" in llm.system_seen
