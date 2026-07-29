import asyncio

import pytest
from langgraph.checkpoint.memory import MemorySaver

from app.graph.builder import build_graph
from app.graph.nodes import make_pm_node
from app.runner import TaskManager
from app.schemas import StartTaskRequest
from tests.conftest import make_fake_factory, fake_test_runner_ok

CODE = "===FILE: calc.py===\ndef add(a, b):\n    return a + b\n===END==="
TESTF = "===FILE: test_calc.py===\ndef test_add():\n    assert True\n===END==="


def test_node_accumulates_tokens():
    node = make_pm_node(make_fake_factory({"pm": ["PRD"]}, {"pm": [(100, 50)]}))
    out = node({"requirement": "计算器", "input_tokens": 10, "output_tokens": 20})
    assert out["input_tokens"] == 110
    assert out["output_tokens"] == 70


def test_node_without_usage_keeps_totals():
    node = make_pm_node(make_fake_factory({"pm": ["PRD"]}))
    out = node({"requirement": "计算器", "input_tokens": 5, "output_tokens": 6})
    assert out["input_tokens"] == 5
    assert out["output_tokens"] == 6


@pytest.mark.asyncio
async def test_task_done_event_carries_token_totals(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    factory = make_fake_factory(
        {"pm": ["PRD"], "architect": ["设计"], "coder": [CODE],
         "tester": [TESTF], "reviewer": ["PASS\n好"]},
        {"pm": [(100, 10)], "architect": [(200, 20)], "coder": [(300, 30)],
         "tester": [(400, 40)], "reviewer": [(500, 50)]})
    graph = build_graph(factory, MemorySaver(), fake_test_runner_ok)
    mgr = TaskManager(graph)
    mgr.start(StartTaskRequest(task_id="TK1", requirement="计算器", auto_mode=True))

    async def collect():
        events = []
        async for ev in mgr.stream("TK1"):
            events.append(ev)
        return events

    events = await asyncio.wait_for(collect(), 10)
    done = events[-1]
    assert done.event == "task_done"
    assert done.data["input_tokens"] == 1500
    assert done.data["output_tokens"] == 150
    # node_end 事件应携带截至该节点的累计值
    pm_end = next(e for e in events if e.event == "node_end" and e.agent == "pm")
    assert pm_end.data["input_tokens"] == 100
