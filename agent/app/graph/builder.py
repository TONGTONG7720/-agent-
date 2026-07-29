from langgraph.graph import END, START, StateGraph

from ..config import settings
from ..state import GraphState
from .nodes import (
    make_architect_node, make_coder_node, make_human_gate,
    make_pm_node, make_reviewer_node, make_tester_node,
)


def build_graph(llm_factory, checkpointer, test_runner):
    """组装设计文档 3.2 的状态图。"""
    g = StateGraph(GraphState)
    g.add_node("pm", make_pm_node(llm_factory))
    g.add_node("prd_gate", make_human_gate("prd_gate", "请确认 PRD"))
    g.add_node("architect", make_architect_node(llm_factory))
    g.add_node("design_gate", make_human_gate("design_gate", "请确认设计文档"))
    g.add_node("coder", make_coder_node(llm_factory))
    g.add_node("tester", make_tester_node(llm_factory, test_runner))
    g.add_node("reviewer", make_reviewer_node(llm_factory))
    g.add_node("accept_gate", make_human_gate("accept_gate", "请最终验收"))

    g.add_edge("pm", "prd_gate")
    g.add_conditional_edges(
        "prd_gate",
        lambda s: "pm" if s.get("human_feedback") else "architect",
        {"pm": "pm", "architect": "architect"},
    )
    g.add_edge("architect", "design_gate")
    g.add_conditional_edges(
        "design_gate",
        _after_design_gate,
        {"pm": "pm", "architect": "architect", "coder": "coder"},
    )
    g.add_edge("coder", "tester")
    g.add_edge("tester", "reviewer")

    def after_review(s: GraphState) -> str:
        if s.get("review_passed") or s.get("iteration_count", 0) >= settings.max_fix_rounds:
            return "accept_gate"
        return "coder"

    g.add_conditional_edges("reviewer", after_review,
                            {"accept_gate": "accept_gate", "coder": "coder"})
    g.add_conditional_edges(
        "accept_gate",
        _after_accept_gate,
        {"pm": "pm", "architect": "architect", "coder": "coder", "__end__": END},
    )
    # 条件入口：多轮迭代（iterate_feedback 非空）直接进 coder，否则从 pm 开始
    g.add_conditional_edges(
        START,
        lambda s: "coder" if s.get("iterate_feedback") else "pm",
        {"coder": "coder", "pm": "pm"},
    )
    return g.compile(checkpointer=checkpointer)


def _after_design_gate(s: GraphState) -> str:
    """设计门：驳回可定向回 pm，默认回 architect；通过进 coder。"""
    if s.get("human_feedback"):
        return "pm" if s.get("reject_target") == "pm" else "architect"
    return "coder"


def _after_accept_gate(s: GraphState) -> str:
    """终审门：驳回定向回 coder(默认)/architect/pm；通过则结束。"""
    if s.get("human_feedback"):
        target = s.get("reject_target")
        return target if target in ("pm", "architect") else "coder"
    return "__end__"
