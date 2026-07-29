from unittest.mock import patch

from app import sandbox
from app.sandbox import build_docker_cmd, run_pytest


def test_build_docker_cmd_has_isolation_flags(monkeypatch):
    monkeypatch.setattr(sandbox.settings, "sandbox_image", "magent-sandbox:latest")
    monkeypatch.setattr(sandbox.settings, "sandbox_memory", "512m")
    monkeypatch.setattr(sandbox.settings, "sandbox_cpus", "1.0")
    cmd = build_docker_cmd("C:\\ws\\T9")
    assert cmd[:3] == ["docker", "run", "--rm"]
    assert "--network" in cmd and cmd[cmd.index("--network") + 1] == "none"   # 断网
    assert "-m" in cmd and cmd[cmd.index("-m") + 1] == "512m"                 # 限内存
    assert "--cpus" in cmd and cmd[cmd.index("--cpus") + 1] == "1.0"          # 限CPU
    assert "-v" in cmd and cmd[cmd.index("-v") + 1] == "C:\\ws\\T9:/work"     # 挂载任务目录
    assert "magent-sandbox:latest" in cmd
    # 容器内跑 pytest
    assert cmd[-4:] == ["python", "-m", "pytest", "-v"] or "pytest" in cmd


def test_run_pytest_dispatches_subprocess_mode(monkeypatch):
    monkeypatch.setattr(sandbox.settings, "sandbox_mode", "subprocess")
    with patch("app.sandbox._exec", return_value=(0, "ok")) as m:
        code, out = run_pytest("C:\\ws\\T9")
    assert code == 0
    called = m.call_args[0][0]
    assert called[0] == "python" and "pytest" in called          # 本机直跑


def test_run_pytest_dispatches_docker_mode(monkeypatch):
    monkeypatch.setattr(sandbox.settings, "sandbox_mode", "docker")
    with patch("app.sandbox._exec", return_value=(0, "ok")) as m:
        run_pytest("C:\\ws\\T9")
    called = m.call_args[0][0]
    assert called[0] == "docker" and "--network" in called       # 容器隔离跑
    # docker 模式下 cwd 不传给 _exec（工作目录靠 -v 挂载）
    assert m.call_args[0][1] is None
