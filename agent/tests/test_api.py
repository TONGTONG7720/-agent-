import json
import time

import pytest
from fastapi.testclient import TestClient
from langgraph.checkpoint.memory import MemorySaver

from app.graph.builder import build_graph
from app.main import create_app
from app.runner import TaskManager
from tests.conftest import make_fake_factory, fake_test_runner_ok

CODE = "===FILE: calc.py===\ndef add(a, b):\n    return a + b\n===END==="
TESTF = "===FILE: test_calc.py===\ndef test_add():\n    assert True\n===END==="
HEADERS = {"X-Internal-Token": "change-me"}


@pytest.fixture
def client(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    graph = build_graph(
        make_fake_factory({"pm": ["PRD"], "architect": ["设计"], "coder": [CODE],
                           "tester": [TESTF], "reviewer": ["PASS\n好"]}),
        MemorySaver(), fake_test_runner_ok)
    app = create_app(TaskManager(graph))
    # 必须用上下文管理器：保证所有请求共用同一事件循环，后台任务与SSE队列才能互通
    with TestClient(app) as c:
        yield c


def test_auth_required(client):
    r = client.post("/agent/tasks", json={"task_id": "T1", "requirement": "x"})
    assert r.status_code == 401


def test_start_and_stream_done(client):
    r = client.post("/agent/tasks", headers=HEADERS,
                    json={"task_id": "T1", "requirement": "计算器", "auto_mode": True})
    assert r.status_code == 200
    events = []
    with client.stream("GET", "/agent/tasks/T1/stream", headers=HEADERS) as resp:
        for line in resp.iter_lines():
            if line.startswith("data:"):
                events.append(json.loads(line[5:]))
    assert events[-1]["event"] == "task_done"


def _wait_suspended(client, task_id, timeout=5.0):
    """等任务挂起在人审门（后台 job 跑完当前阶段）。"""
    mgr = client.app.state.manager
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = mgr.jobs.get(task_id)
        if job and job.done():
            return
        time.sleep(0.05)
    raise TimeoutError(f"task {task_id} not suspended in {timeout}s")


def test_resume_flow(client):
    # httpx ASGI 传输会缓冲完整响应，SSE 无法边读边 resume；
    # 改为：轮询等挂起 → resume 过三道门 → 最后一次性读完事件流验证序列
    client.post("/agent/tasks", headers=HEADERS,
                json={"task_id": "T2", "requirement": "计算器", "auto_mode": False})
    for _ in range(3):                                # 连过三道人审门
        _wait_suspended(client, "T2")
        r = client.post("/agent/tasks/T2/resume", headers=HEADERS,
                        json={"decision": "pass", "comment": ""})
        assert r.status_code == 200
    _wait_suspended(client, "T2")                     # 等最终完成
    kinds = []
    with client.stream("GET", "/agent/tasks/T2/stream", headers=HEADERS) as resp:
        for line in resp.iter_lines():
            if line.startswith("data:"):
                kinds.append(json.loads(line[5:])["event"])
    assert kinds.count("interrupt") == 3              # 三道人审门都触发过
    assert kinds[-1] == "task_done"


def test_resume_unknown_task_404(client):
    r = client.post("/agent/tasks/NOPE/resume", headers=HEADERS,
                    json={"decision": "pass", "comment": ""})
    assert r.status_code == 404
