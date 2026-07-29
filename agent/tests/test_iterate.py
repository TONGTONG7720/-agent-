import asyncio

import pytest
from langgraph.checkpoint.memory import MemorySaver
from langgraph.types import Command

from app.graph.builder import build_graph
from app.runner import TaskManager
from app.schemas import StartTaskRequest
from tests.conftest import make_fake_factory, fake_test_runner_ok

CODE_V1 = "===FILE: calc.py===\ndef add(a, b):\n    return a + b\n===END==="
CODE_V2 = "===FILE: calc.py===\ndef add(a, b):\n    return a + b  # v2\n===END==="
TESTF = "===FILE: test_calc.py===\ndef test_add():\n    assert True\n===END==="


def _cfg(tid):
    return {"configurable": {"thread_id": tid}}


def _base(tid, auto=True):
    return {"task_id": tid, "requirement": "做计算器", "auto_mode": auto,
            "iteration_count": 0, "role_models": {}}


async def _collect(mgr, task_id):
    events = []
    async for ev in mgr.stream(task_id):
        events.append(ev)
    return events


# ---------- 多轮迭代 ----------

@pytest.mark.asyncio
async def test_iterate_after_done_only_reruns_coder_pipeline(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    graph = build_graph(make_fake_factory(
        {"pm": ["PRD"], "architect": ["设计"], "coder": [CODE_V1, CODE_V2],
         "tester": [TESTF, TESTF], "reviewer": ["PASS\n好", "PASS\n好"]}),
        MemorySaver(), fake_test_runner_ok)
    mgr = TaskManager(graph)

    mgr.start(StartTaskRequest(task_id="I1", requirement="做计算器", auto_mode=True))
    events = await asyncio.wait_for(_collect(mgr, "I1"), 10)
    assert events[-1].event == "task_done"

    mgr.iterate("I1", "加法结果加注释")
    events = await asyncio.wait_for(_collect(mgr, "I1"), 10)
    assert events[-1].event == "task_done"
    # 第二轮只经过 coder/tester/reviewer，不重跑 pm/architect
    round2_agents = [e.agent for e in events if e.event == "node_end"]
    assert "pm" not in round2_agents and "architect" not in round2_agents
    assert "coder" in round2_agents
    # 代码确实更新为 v2
    state = graph.get_state(_cfg("I1")).values
    assert "v2" in state["code_files"][0]["content"]
    # 保留了第一轮的 PRD/设计
    assert state["prd"] == "PRD" and state["design_doc"] == "设计"


@pytest.mark.asyncio
async def test_iterate_unknown_task_raises(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    graph = build_graph(make_fake_factory({"pm": ["PRD"]}), MemorySaver(), fake_test_runner_ok)
    mgr = TaskManager(graph)
    with pytest.raises(KeyError):
        mgr.iterate("NOPE", "x")


# ---------- 定向回退 ----------

def _graph(responses):
    return build_graph(make_fake_factory(responses), MemorySaver(), fake_test_runner_ok)


def test_accept_gate_reject_default_returns_to_coder(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    g = _graph({"pm": ["PRD"], "architect": ["设计"], "coder": [CODE_V1, CODE_V2],
                "tester": [TESTF, TESTF], "reviewer": ["PASS\n好", "PASS\n好"]})
    cfg = _cfg("G1")
    g.invoke(_base("G1", auto=False), cfg)                                   # 停 PRD 门
    g.invoke(Command(resume={"decision": "pass"}), cfg)                       # 停 设计门
    g.invoke(Command(resume={"decision": "pass"}), cfg)                       # 停 终审门
    g.invoke(Command(resume={"decision": "reject", "comment": "细节不够"}), cfg)  # 默认回 coder → 再停终审
    state = g.get_state(cfg).values
    assert "v2" in state["code_files"][0]["content"]                          # coder 重跑过
    final = g.invoke(Command(resume={"decision": "pass"}), cfg)
    assert final["review_passed"] is True


def test_accept_gate_reject_target_architect(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    g = _graph({"pm": ["PRD"], "architect": ["设计v1", "设计v2"], "coder": [CODE_V1, CODE_V2],
                "tester": [TESTF, TESTF], "reviewer": ["PASS\n好", "PASS\n好"]})
    cfg = _cfg("G2")
    g.invoke(_base("G2", auto=False), cfg)
    g.invoke(Command(resume={"decision": "pass"}), cfg)
    g.invoke(Command(resume={"decision": "pass"}), cfg)                       # 停 终审
    g.invoke(Command(resume={"decision": "reject", "comment": "架构改", "target": "architect"}), cfg)
    # 回到 architect 会重新产设计 → 停在设计门
    state = g.get_state(cfg).values
    assert state["design_doc"] == "设计v2"


def test_design_gate_reject_target_pm(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    g = _graph({"pm": ["PRD-v1", "PRD-v2"], "architect": ["设计"], "coder": [CODE_V1],
                "tester": [TESTF], "reviewer": ["PASS\n好"]})
    cfg = _cfg("G3")
    g.invoke(_base("G3", auto=False), cfg)                                    # 停 PRD 门
    g.invoke(Command(resume={"decision": "pass"}), cfg)                       # 停 设计门
    g.invoke(Command(resume={"decision": "reject", "comment": "需求就错了", "target": "pm"}), cfg)
    state = g.get_state(cfg).values
    assert state["prd"] == "PRD-v2"                                           # 回到了 pm
