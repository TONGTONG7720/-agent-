import subprocess

from .config import settings

ALLOWED_COMMANDS = {"python", "pytest", "node", "npm", "npx"}
MAX_OUTPUT = 64 * 1024


def _exec(cmd: list[str], cwd: str | None, timeout: int | None = None) -> tuple[int, str]:
    """底层执行：限时 + 输出截断 + 命令缺失友好报错。"""
    try:
        proc = subprocess.run(
            cmd, cwd=cwd, capture_output=True, text=True,
            timeout=timeout or settings.test_timeout_seconds,
        )
        return proc.returncode, (proc.stdout + proc.stderr)[:MAX_OUTPUT]
    except subprocess.TimeoutExpired:
        return -1, "TIMEOUT: 测试执行超时"
    except FileNotFoundError:
        return -1, f"命令不可用: {cmd[0]}（docker 模式需 Docker Desktop 处于运行状态）"


def run_command(cmd: list[str], cwd: str, timeout: int | None = None) -> tuple[int, str]:
    """白名单 + 限时 + 输出截断的命令执行（设计文档第 5 节）。"""
    if not cmd or cmd[0] not in ALLOWED_COMMANDS:
        raise ValueError(f"command not allowed: {cmd[:1]}")
    return _exec(cmd, cwd, timeout)


def build_docker_cmd(cwd: str) -> list[str]:
    """构造容器隔离执行命令：断网 + 内存/CPU 限额 + 任务目录挂载为 /work。"""
    return [
        "docker", "run", "--rm",
        "--network", "none",
        "-m", settings.sandbox_memory,
        "--cpus", settings.sandbox_cpus,
        "-v", f"{cwd}:/work",
        "-w", "/work",
        settings.sandbox_image,
        "python", "-m", "pytest", "-v", "--tb=short",
    ]


def run_pytest(cwd: str) -> tuple[int, str]:
    """按 sandbox_mode 分派：docker 容器隔离 / 本机 subprocess。"""
    if settings.sandbox_mode == "docker":
        return _exec(build_docker_cmd(cwd), None)
    return _exec(["python", "-m", "pytest", "-v", "--tb=short"], cwd)
