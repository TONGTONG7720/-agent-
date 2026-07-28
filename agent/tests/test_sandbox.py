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
