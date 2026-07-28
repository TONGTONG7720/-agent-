import os

from .config import settings


def task_dir(task_id: str) -> str:
    d = os.path.join(settings.workspace_root, task_id)
    os.makedirs(d, exist_ok=True)
    return d


def write_files(task_id: str, files: list[dict]) -> list[str]:
    """把代码/文档文件写入任务工作目录，返回绝对路径列表。"""
    base = os.path.abspath(task_dir(task_id))
    written = []
    for f in files:
        path = os.path.abspath(os.path.join(base, f["path"]))
        # 用 commonpath 判定层级包含，避免 startswith 的同前缀目录绕过（如 T1 → T1-other）
        if os.path.commonpath([base, path]) != base:
            raise ValueError(f"path escape: {f['path']}")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fp:
            fp.write(f["content"])
        written.append(path)
    return written
