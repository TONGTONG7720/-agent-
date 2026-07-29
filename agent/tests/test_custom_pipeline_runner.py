"""B4-2b: TaskManager 按自定义 pipeline 建任务专属图并跑通事件流。"""
import asyncio

import pytest
from langgraph.checkpoint.memory import MemorySaver

from app.graph.builder import build_graph
from app.graph.pipeline import build_pipeline_graph
from app.runner import TaskManager
from app.schemas import StartTaskRequest
from tests.conftest import make_fake_factory, fake_test_runner_ok

CODE = "===FILE: calc.py===\ndef add(a, b):\n    return a + b\n===END==="


async def _collect(mgr, task_id):
    events = []
    async for ev in mgr.stream(task_id):
        events.append(ev)
    return events


@pytest.mark.asyncio
async def test_custom_pipeline_task_runs_via_manager(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    factory = make_fake_factory({"pm": ["PRD"], "security": ["安全要点"],
                                 "coder": [CODE], "reviewer": ["PASS\n好"]})
    # 默认图（兜底）+ pipeline 构建器
    default_graph = build_graph(factory, MemorySaver(), fake_test_runner_ok)

    def pipeline_builder(spec):
        return build_pipeline_graph(spec, factory, MemorySaver(), fake_test_runner_ok)

    mgr = TaskManager(default_graph, pipeline_builder=pipeline_builder)

    spec = {"steps": [
        {"key": "pm", "name": "产品", "kind": "analysis", "gate": False},
        {"key": "security", "name": "安全审计员", "kind": "analysis", "gate": False},
        {"key": "coder", "name": "开发", "kind": "code", "gate": False},
        {"key": "reviewer", "name": "审查", "kind": "review", "gate": False, "rework_target": "coder"},
    ], "final_gate": True}
    mgr.start(StartTaskRequest(task_id="CP1", requirement="计算器", auto_mode=True, pipeline=spec))

    events = await asyncio.wait_for(_collect(mgr, "CP1"), 10)
    kinds = [e.event for e in events]
    assert kinds[-1] == "task_done"
    # 自定义分析角色 security 也产出了 artifact
    art_names = [e.data.get("name") for e in events if e.event == "artifact_created"]
    assert any("security" in (n or "") for n in art_names)
    # 生成了代码文件 artifact
    assert any((n or "").endswith(".py") for n in art_names)


@pytest.mark.asyncio
async def test_default_task_without_pipeline_still_works(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    TESTF = "===FILE: test_calc.py===\ndef test_add():\n    assert True\n===END==="
    factory = make_fake_factory({"pm": ["PRD"], "architect": ["设计"], "coder": [CODE],
                                 "tester": [TESTF], "reviewer": ["PASS\n好"]})
    mgr = TaskManager(build_graph(factory, MemorySaver(), fake_test_runner_ok))
    mgr.start(StartTaskRequest(task_id="CP2", requirement="计算器", auto_mode=True))
    events = await asyncio.wait_for(_collect(mgr, "CP2"), 10)
    assert events[-1].event == "task_done"
