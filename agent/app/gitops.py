"""任务产物推送到 Git 仓库（新分支）。

设计要点（见 B3 计划）：
- 在任务目录内 git init（幂等）→ add/commit → 直推 URL 到新分支（孤立历史合法）
- https 地址才注入 Token；报错信息脱敏，绝不回显含 Token 的 URL
- 本地路径/file:// 远端同样支持（离线测试用）
"""
import os
import subprocess
import time

from .workspace import task_dir

_GIT_IDENT = ["-c", "user.name=magent-bot", "-c", "user.email=magent-bot@local"]
_TIMEOUT = 120


def _with_token(repo_url: str, token: str | None) -> str:
    """仅对 https 地址注入 Token（作为 basic 凭据用户名）。"""
    if token and repo_url.startswith("https://"):
        return "https://" + token + "@" + repo_url[len("https://"):]
    return repo_url


def _sanitize(text: str, token: str | None) -> str:
    return text.replace(token, "***") if token else text


def _run(args: list[str], cwd: str, token: str | None = None) -> str:
    proc = subprocess.run(["git", *_GIT_IDENT, *args], cwd=cwd,
                          capture_output=True, text=True, timeout=_TIMEOUT)
    if proc.returncode != 0:
        detail = _sanitize((proc.stderr or proc.stdout).strip()[:300], token)
        raise RuntimeError(f"git {args[0]} 失败: {detail}")
    return proc.stdout


def push_task(task_id: str, repo_url: str, token: str | None = None,
              branch: str | None = None) -> str:
    """把 workspace/{task_id} 全部产物提交并推送到远端新分支，返回分支名。"""
    cwd = task_dir(task_id)
    has_files = any(name != ".git" for name in os.listdir(cwd))
    if not has_files:
        raise RuntimeError("任务没有可推送的产物")

    branch = branch or f"magent/{task_id}-{time.strftime('%Y%m%d%H%M%S')}"
    url = _with_token(repo_url, token)

    if not os.path.isdir(os.path.join(cwd, ".git")):
        _run(["init"], cwd, token)
    _run(["add", "-A"], cwd, token)

    commit = subprocess.run(
        ["git", *_GIT_IDENT, "commit", "-m", f"chore: artifacts of task {task_id}"],
        cwd=cwd, capture_output=True, text=True, timeout=_TIMEOUT)
    output = (commit.stdout or "") + (commit.stderr or "")
    if commit.returncode != 0 and "nothing to commit" not in output:
        raise RuntimeError(f"git commit 失败: {_sanitize(output.strip()[:300], token)}")

    _run(["push", url, f"HEAD:refs/heads/{branch}"], cwd, token)
    return branch
