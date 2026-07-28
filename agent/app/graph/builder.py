from langgraph.graph import END, StateGraph

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

    g.set_entry_point("pm")
    g.add_edge("pm", "prd_gate")
    g.add_conditional_edges(
        "prd_gate",
        lambda s: "pm" if s.get("human_feedback") else "architect",
        {"pm": "pm", "architect": "architect"},
    )
    g.add_edge("architect", "design_gate")
    g.add_conditional_edges(
        "design_gate",
        lambda s: "architect" if s.get("human_feedback") else "coder",
        {"architect": "architect", "coder": "coder"},
    )
    g.add_edge("coder", "tester")
    g.add_edge("tester", "reviewer")

    def after_review(s: GraphState) -> str:
        if s.get("review_passed") or s.get("iteration_count", 0) >= settings.max_fix_rounds:
            return "accept_gate"
        return "coder"

    g.add_conditional_edges("reviewer", after_review,
                            {"accept_gate": "accept_gate", "coder": "coder"})
    g.add_edge("accept_gate", END)
    return g.compile(checkpointer=checkpointer)
