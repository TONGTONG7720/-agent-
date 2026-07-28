from langgraph.checkpoint.memory import MemorySaver
from langgraph.types import Command

from app.graph.builder import build_graph
from tests.conftest import make_fake_factory, fake_test_runner_ok

CODE = "===FILE: calc.py===\ndef add(a, b):\n    return a + b\n===END==="
TESTF = "===FILE: test_calc.py===\ndef test_add():\n    assert True\n===END==="


def _graph(responses, runner=fake_test_runner_ok):
    return build_graph(make_fake_factory(responses), MemorySaver(), runner)


def _cfg(tid):
    return {"configurable": {"thread_id": tid}}


def _base(tid, auto):
    return {"task_id": tid, "requirement": "做计算器", "auto_mode": auto,
            "iteration_count": 0, "role_models": {}}


def test_auto_mode_happy_path(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    g = _graph({"pm": ["PRD"], "architect": ["设计"], "coder": [CODE],
                "tester": [TESTF], "reviewer": ["PASS\n好"]})
    result = g.invoke(_base("T1", True), _cfg("T1"))
    assert result["prd"] == "PRD" and result["review_passed"] is True
    assert "2 passed" in result["test_report"]


def test_review_fail_loops_back_to_coder(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    g = _graph({"pm": ["PRD"], "architect": ["设计"], "coder": [CODE, CODE],
                "tester": [TESTF, TESTF], "reviewer": ["FAIL\n改", "PASS\n好"]})
    result = g.invoke(_base("T2", True), _cfg("T2"))
    assert result["review_passed"] is True and result["iteration_count"] == 1


def test_max_rounds_forces_exit(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    g = _graph({"pm": ["PRD"], "architect": ["设计"], "coder": [CODE] * 4,
                "tester": [TESTF] * 4, "reviewer": ["FAIL\n改"] * 4})
    result = g.invoke(_base("T3", True), _cfg("T3"))
    assert result["review_passed"] is False and result["iteration_count"] == 3


def test_interrupt_and_reject_reruns_pm(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    g = _graph({"pm": ["PRD-v1", "PRD-v2"], "architect": ["设计"], "coder": [CODE],
                "tester": [TESTF], "reviewer": ["PASS\n好"]})
    cfg = _cfg("T4")
    r1 = g.invoke(_base("T4", False), cfg)
    assert "__interrupt__" in r1                      # 停在 PRD 人审门
    r2 = g.invoke(Command(resume={"decision": "reject", "comment": "重写"}), cfg)
    assert "__interrupt__" in r2                      # 驳回后重跑 PM，再次停在门口
    assert g.get_state(cfg).values["prd"] == "PRD-v2"
    g.invoke(Command(resume={"decision": "pass"}), cfg)      # 过 PRD 门 → 停设计门
    g.invoke(Command(resume={"decision": "pass"}), cfg)      # 过设计门 → 停最终验收
    final = g.invoke(Command(resume={"decision": "pass"}), cfg)
    assert final["review_passed"] is True
