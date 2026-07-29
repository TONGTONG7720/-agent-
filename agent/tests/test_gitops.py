import subprocess

import pytest

from app.gitops import push_task, _with_token


def _git(args, cwd):
    return subprocess.run(["git"] + args, cwd=cwd, capture_output=True, text=True)


@pytest.fixture
def bare_repo(tmp_path):
    """本地 bare 仓库充当远端（file 路径推送不走网络）。"""
    remote = tmp_path / "remote.git"
    remote.mkdir()
    assert _git(["init", "--bare"], str(remote)).returncode == 0
    return str(remote)


@pytest.fixture
def task_ws(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path / "ws"))
    d = tmp_path / "ws" / "TG1"
    d.mkdir(parents=True)
    (d / "PRD.md").write_text("# PRD", encoding="utf-8")
    (d / "calc.py").write_text("X = 1", encoding="utf-8")
    return "TG1"


def test_push_creates_branch_with_files(bare_repo, task_ws):
    branch = push_task(task_ws, bare_repo)
    assert branch.startswith("magent/TG1-")
    # 远端确实有该分支
    out = _git(["branch"], bare_repo).stdout
    assert branch in out
    # 分支上包含产物文件
    files = _git(["ls-tree", "-r", "--name-only", branch], bare_repo).stdout
    assert "PRD.md" in files and "calc.py" in files


def test_push_twice_creates_two_branches(bare_repo, task_ws):
    b1 = push_task(task_ws, bare_repo, branch="magent/TG1-a")
    b2 = push_task(task_ws, bare_repo, branch="magent/TG1-b")   # 产物没变也允许
    out = _git(["branch"], bare_repo).stdout
    assert b1 in out and b2 in out


def test_push_empty_workspace_raises(tmp_path, monkeypatch, bare_repo):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path / "ws2"))
    with pytest.raises(RuntimeError):
        push_task("T-EMPTY", bare_repo)


def test_token_injected_only_for_https():
    assert _with_token("https://github.com/u/r.git", "tok123") == "https://tok123@github.com/u/r.git"
    assert _with_token("https://github.com/u/r.git", None) == "https://github.com/u/r.git"
    # 非 https 不注入
    assert _with_token("/some/local/path.git", "tok123") == "/some/local/path.git"


def test_error_message_never_leaks_token(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path / "ws3"))
    d = tmp_path / "ws3" / "TG2"
    d.mkdir(parents=True)
    (d / "a.txt").write_text("x", encoding="utf-8")
    with pytest.raises(RuntimeError) as ei:
        # 指向不存在的 https 地址且带 token —— 报错信息不得包含 token
        push_task("TG2", "https://127.0.0.1:1/nonexistent.git", token="SECRET-TOKEN-XYZ")
    assert "SECRET-TOKEN-XYZ" not in str(ei.value)
