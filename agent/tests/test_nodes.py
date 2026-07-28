from app.graph.nodes import (
    make_pm_node, make_architect_node, make_coder_node,
    make_tester_node, make_reviewer_node,
)
from tests.conftest import make_fake_factory, fake_test_runner_ok

CODE_REPLY = "===FILE: calc.py===\ndef add(a, b):\n    return a + b\n===END==="
TEST_REPLY = "===FILE: test_calc.py===\nfrom calc import add\n\ndef test_add():\n    assert add(1, 2) == 3\n===END==="


def test_pm_node():
    node = make_pm_node(make_fake_factory({"pm": ["PRD内容"]}))
    out = node({"requirement": "做一个计算器", "role_models": {}})
    assert out["prd"] == "PRD内容"


def test_architect_node():
    node = make_architect_node(make_fake_factory({"architect": ["设计文档"]}))
    out = node({"prd": "PRD内容"})
    assert out["design_doc"] == "设计文档"


def test_coder_node_parses_files():
    node = make_coder_node(make_fake_factory({"coder": [CODE_REPLY]}))
    out = node({"design_doc": "设计", "iteration_count": 0})
    assert out["code_files"][0]["path"] == "calc.py"


def test_tester_node(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    node = make_tester_node(make_fake_factory({"tester": [TEST_REPLY]}), fake_test_runner_ok)
    out = node({"task_id": "T1", "code_files": [{"path": "calc.py", "content": "def add(a,b):\n    return a+b"}]})
    assert "2 passed" in out["test_report"]


def test_reviewer_pass_and_fail():
    node = make_reviewer_node(make_fake_factory({"reviewer": ["PASS\n代码质量良好", "FAIL\n缺少边界处理"]}))
    ok = node({"code_files": [], "test_report": "1 passed", "iteration_count": 0})
    assert ok["review_passed"] is True
    bad = node({"code_files": [], "test_report": "1 failed", "iteration_count": 0})
    assert bad["review_passed"] is False and bad["iteration_count"] == 1
    assert "缺少边界处理" in bad["review_comments"]
