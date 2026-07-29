import asyncio

import pytest
from fastapi.testclient import TestClient
from langgraph.checkpoint.memory import MemorySaver

from app.graph.builder import build_graph
from app.main import create_app
from app.runner import TaskManager
from app.schemas import StartTaskRequest
from tests.conftest import make_fake_factory, fake_test_runner_ok

CODE = "===FILE: calc.py===\ndef add(a, b):\n    return a + b\n===END==="
TESTF = "===FILE: test_calc.py===\ndef test_add():\n    assert True\n===END==="
HEADERS = {"X-Internal-Token": "change-me"}


class FlakyLLM:
    """第一次调用抛异常，之后正常返回——模拟网关抖动后恢复。"""

    def __init__(self, responses: list[str]):
        self.responses = list(responses)
        self.failed_once = False

    def invoke(self, messages):
        if not self.failed_once:
            self.failed_once = True
            raise ConnectionError("gateway down")
        from langchain_core.messages import AIMessage
        return AIMessage(content=self.responses.pop(0))


def make_flaky_factory():
    """pm 首调失败，其余角色正常。"""
    ok = make_fake_factory({"architect": ["设计"], "coder": [CODE],
                            "tester": [TESTF], "reviewer": ["PASS\n好"]})
    pm = FlakyLLM(["PRD"])

    def factory(role: str, state: dict):
        return pm if role == "pm" else ok(role, state)
    return factory


async def _collect(mgr, task_id):
    events = []
    async for ev in mgr.stream(task_id):
        events.append(ev)
    return events


@pytest.mark.asyncio
async def test_retry_resumes_from_checkpoint(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    graph = build_graph(make_flaky_factory(), MemorySaver(), fake_test_runner_ok)
    mgr = TaskManager(graph)

    mgr.start(StartTaskRequest(task_id="R1", requirement="计算器", auto_mode=True))
    events = await asyncio.wait_for(_collect(mgr, "R1"), 10)
    assert events[-1].event == "task_failed"          # 首跑失败

    mgr.retry("R1")                                    # 从 checkpoint 续跑
    events = await asyncio.wait_for(_collect(mgr, "R1"), 10)
    assert events[-1].event == "task_done"
    assert events[-1].data["review_passed"] is True


@pytest.mark.asyncio
async def test_retry_unknown_task_raises(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    graph = build_graph(make_flaky_factory(), MemorySaver(), fake_test_runner_ok)
    mgr = TaskManager(graph)
    with pytest.raises(KeyError):
        mgr.retry("NOPE")


def test_retry_endpoint(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    graph = build_graph(make_flaky_factory(), MemorySaver(), fake_test_runner_ok)
    app = create_app(TaskManager(graph))
    with TestClient(app) as client:
        # 未知任务 404
        r = client.post("/agent/tasks/NOPE/retry", headers=HEADERS)
        assert r.status_code == 404
        # 启动（首跑失败）→ retry 200 → 流最终 task_done
        client.post("/agent/tasks", headers=HEADERS,
                    json={"task_id": "R2", "requirement": "计算器", "auto_mode": True})
        import json as _json
        with client.stream("GET", "/agent/tasks/R2/stream", headers=HEADERS) as resp:
            last = [_json.loads(l[5:]) for l in resp.iter_lines() if l.startswith("data:")][-1]
        assert last["event"] == "task_failed"
        r = client.post("/agent/tasks/R2/retry", headers=HEADERS)
        assert r.status_code == 200
        with client.stream("GET", "/agent/tasks/R2/stream", headers=HEADERS) as resp:
            last = [_json.loads(l[5:]) for l in resp.iter_lines() if l.startswith("data:")][-1]
        assert last["event"] == "task_done"
