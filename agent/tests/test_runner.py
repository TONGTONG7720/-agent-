import asyncio

import pytest
from langgraph.checkpoint.memory import MemorySaver

from app.graph.builder import build_graph
from app.runner import TaskManager
from app.schemas import StartTaskRequest
from tests.conftest import make_fake_factory, fake_test_runner_ok

CODE = "===FILE: calc.py===\ndef add(a, b):\n    return a + b\n===END==="
TESTF = "===FILE: test_calc.py===\ndef test_add():\n    assert True\n===END==="


def _manager(responses):
    graph = build_graph(make_fake_factory(responses), MemorySaver(), fake_test_runner_ok)
    return TaskManager(graph)


async def _collect(mgr, task_id, stop_events):
    events = []
    async for ev in mgr.stream(task_id):
        events.append(ev)
        if ev.event in stop_events:
            break
    return events


@pytest.mark.asyncio
async def test_auto_task_emits_done(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    mgr = _manager({"pm": ["PRD"], "architect": ["设计"], "coder": [CODE],
                    "tester": [TESTF], "reviewer": ["PASS\n好"]})
    mgr.start(StartTaskRequest(task_id="T1", requirement="计算器", auto_mode=True))
    events = await asyncio.wait_for(_collect(mgr, "T1", {"task_done", "task_failed"}), 10)
    kinds = [e.event for e in events]
    assert kinds[-1] == "task_done"
    assert "agent_message" in kinds and "artifact_created" in kinds
    seqs = [e.seq for e in events]
    assert seqs == sorted(seqs) and len(set(seqs)) == len(seqs)


@pytest.mark.asyncio
async def test_interrupt_then_resume(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    mgr = _manager({"pm": ["PRD"], "architect": ["设计"], "coder": [CODE],
                    "tester": [TESTF], "reviewer": ["PASS\n好"]})
    mgr.start(StartTaskRequest(task_id="T2", requirement="计算器", auto_mode=False))
    events = await asyncio.wait_for(_collect(mgr, "T2", {"interrupt"}), 10)
    assert events[-1].data["gate"] == "prd_gate"
    for _ in range(3):                                  # 连过三道门
        mgr.resume("T2", "pass", "")
        events = await asyncio.wait_for(
            _collect(mgr, "T2", {"interrupt", "task_done"}), 10)
    assert events[-1].event == "task_done"
