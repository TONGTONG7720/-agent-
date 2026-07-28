import subprocess

from .config import settings

ALLOWED_COMMANDS = {"python", "pytest", "node", "npm", "npx"}
MAX_OUTPUT = 64 * 1024


def run_command(cmd: list[str], cwd: str, timeout: int | None = None) -> tuple[int, str]:
    """白名单 + 限时 + 输出截断的命令执行（设计文档第 5 节）。"""
    if not cmd or cmd[0] not in ALLOWED_COMMANDS:
        raise ValueError(f"command not allowed: {cmd[:1]}")
    try:
        proc = subprocess.run(
            cmd, cwd=cwd, capture_output=True, text=True,
            timeout=timeout or settings.test_timeout_seconds,
        )
        return proc.returncode, (proc.stdout + proc.stderr)[:MAX_OUTPUT]
    except subprocess.TimeoutExpired:
        return -1, "TIMEOUT: 测试执行超时"


def run_pytest(cwd: str) -> tuple[int, str]:
    return run_command(["python", "-m", "pytest", "-v", "--tb=short"], cwd=cwd)
