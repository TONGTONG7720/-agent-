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
    pipeline: dict | None = None                                # 自定义流水线 spec（缺省用默认五角色）


class ResumeRequest(BaseModel):
    decision: str                 # pass | reject
    comment: str = ""
    target: str | None = None     # 驳回定向回退目标（pm/architect/coder，缺省按门默认）


class IterateRequest(BaseModel):
    feedback: str                 # 多轮迭代的修改意见
    after_seq: int | None = None


class RetryRequest(BaseModel):
    after_seq: int | None = None  # server 已落库的最大事件序号，重试后 seq 从其之后续号


class PushRequest(BaseModel):
    repo_url: str                 # 目标仓库（https 或本地路径）
    token: str | None = None      # https 时可选注入；不落盘不回显
    branch: str | None = None     # 缺省自动生成 magent/{taskId}-{ts}
