"""B4-2 动态流水线引擎测试：自定义增删角色 / 调整顺序 / 审查返工 / 人审门。"""
import asyncio

import pytest
from langgraph.checkpoint.memory import MemorySaver
from langgraph.types import Command

from app.graph.pipeline import DEFAULT_PIPELINE, build_pipeline_graph
from tests.conftest import make_fake_factory, fake_test_runner_ok

CODE = "===FILE: calc.py===\ndef add(a, b):\n    return a + b\n===END==="
TESTF = "===FILE: test_calc.py===\ndef test_add():\n    assert True\n===END==="


def _cfg(tid):
    return {"configurable": {"thread_id": tid}}


def _base(tid, auto=True):
    return {"task_id": tid, "requirement": "做计算器", "auto_mode": auto,
            "iteration_count": 0, "role_models": {}}


def test_default_pipeline_runs_end_to_end(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    g = build_pipeline_graph(DEFAULT_PIPELINE, make_fake_factory(
        {"pm": ["PRD"], "architect": ["设计"], "coder": [CODE],
         "tester": [TESTF], "reviewer": ["PASS\n好"]}), MemorySaver(), fake_test_runner_ok)
    result = g.invoke(_base("P1", auto=True), _cfg("P1"))
    assert result["review_passed"] is True
    # documents 累计了五个角色的产出
    keys = [d["key"] for d in result["documents"]]
    assert keys == ["pm", "architect", "coder", "tester", "reviewer"]


def test_custom_add_and_remove_roles(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    # 自定义流水线：需求分析 → 安全审计(新增分析角色) → 开发 → 审查（去掉 tester）
    spec = {"steps": [
        {"key": "pm", "name": "产品", "kind": "analysis", "gate": False},
        {"key": "security", "name": "安全审计员", "kind": "analysis", "gate": False,
         "system_prompt": "你是安全审计员，指出安全要点"},
        {"key": "coder", "name": "开发", "kind": "code", "gate": False},
        {"key": "reviewer", "name": "审查", "kind": "review", "gate": False, "rework_target": "coder"},
    ], "final_gate": True}
    g = build_pipeline_graph(spec, make_fake_factory(
        {"pm": ["PRD"], "security": ["安全要点：输入校验"], "coder": [CODE],
         "reviewer": ["PASS\n好"]}), MemorySaver(), fake_test_runner_ok)
    result = g.invoke(_base("P2", auto=True), _cfg("P2"))
    keys = [d["key"] for d in result["documents"]]
    assert keys == ["pm", "security", "coder", "reviewer"]   # 新增 security，无 tester/architect
    assert result["review_passed"] is True


def test_review_rework_loops_back(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    spec = {"steps": [
        {"key": "coder", "name": "开发", "kind": "code", "gate": False},
        {"key": "reviewer", "name": "审查", "kind": "review", "gate": False, "rework_target": "coder"},
    ], "final_gate": False}
    g = build_pipeline_graph(spec, make_fake_factory(
        {"coder": [CODE, CODE], "reviewer": ["FAIL\n改", "PASS\n好"]}),
        MemorySaver(), fake_test_runner_ok)
    result = g.invoke(_base("P3", auto=True), _cfg("P3"))
    assert result["review_passed"] is True and result["iteration_count"] == 1


def test_custom_gate_interrupts(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    spec = {"steps": [
        {"key": "pm", "name": "产品", "kind": "analysis", "gate": True},
        {"key": "coder", "name": "开发", "kind": "code", "gate": False},
    ], "final_gate": False}
    g = build_pipeline_graph(spec, make_fake_factory(
        {"pm": ["PRD"], "coder": [CODE]}), MemorySaver(), fake_test_runner_ok)
    cfg = _cfg("P4")
    r1 = g.invoke(_base("P4", auto=False), cfg)
    assert "__interrupt__" in r1                       # 停在 pm 的人审门
    final = g.invoke(Command(resume={"decision": "pass"}), cfg)
    keys = [d["key"] for d in final["documents"]]
    assert keys == ["pm", "coder"]
