import time

from pydantic import BaseModel, Field


def _now_ms() -> int:
    return int(time.time() * 1000)


class AgentEvent(BaseModel):
    """SSE 事件协议，见设计文档 4.1。"""
    event: str                    # node_end/agent_message/artifact_created/interrupt/task_done/task_failed
    task_id: str
    agent: str | None = None
    seq: int = 0
    data: dict = Field(default_factory=dict)
    ts: int = Field(default_factory=_now_ms)


class StartTaskRequest(BaseModel):
    task_id: str
    requirement: str
    auto_mode: bool = False
    role_models: dict[str, str] = Field(default_factory=dict)
    role_prompts: dict[str, str] = Field(default_factory=dict)  # 覆盖默认 prompt，可空


class ResumeRequest(BaseModel):
    decision: str                 # pass | reject
    comment: str = ""
