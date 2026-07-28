import pytest

from app.sandbox import run_command
from app.workspace import write_files, task_dir


def test_run_command_ok(tmp_path):
    code, out = run_command(["python", "-c", "print('hi')"], cwd=str(tmp_path))
    assert code == 0 and "hi" in out


def test_run_command_rejects_non_whitelist(tmp_path):
    with pytest.raises(ValueError):
        run_command(["curl", "http://x"], cwd=str(tmp_path))


def test_write_files(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    paths = write_files("T1", [{"path": "a/b.py", "content": "X = 1"}])
    assert (tmp_path / "T1" / "a" / "b.py").read_text(encoding="utf-8") == "X = 1"
    assert paths == [str(tmp_path / "T1" / "a" / "b.py")]
    assert task_dir("T1") == str(tmp_path / "T1")


def test_write_files_rejects_path_escape(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    # 同前缀目录绕过：T1/../T1-other 解析后以 "T1" 开头但不在 T1 目录内
    with pytest.raises(ValueError):
        write_files("T1", [{"path": "../T1-other/x.py", "content": "bad"}])
    with pytest.raises(ValueError):
        write_files("T1", [{"path": "../../outside.py", "content": "bad"}])
