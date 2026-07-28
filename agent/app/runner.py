import asyncio
import logging

from langgraph.types import Command

from .schemas import AgentEvent, StartTaskRequest
from .workspace import write_files

logger = logging.getLogger(__name__)

# 节点结束时要作为产物落盘的字段: 节点 -> (state字段, 产物文件名, 类型)
_ARTIFACTS = {
    "pm": ("prd", "PRD.md", "prd"),
    "architect": ("design_doc", "DESIGN.md", "design"),
    "tester": ("test_report", "TEST_REPORT.md", "test_report"),
}


class TaskManager:
    """任务运行器：启动图执行、事件队列、resume/cancel。"""

    def __init__(self, graph):
        self.graph = graph
        self.queues: dict[str, asyncio.Queue] = {}
        self.seqs: dict[str, int] = {}
        self.jobs: dict[str, asyncio.Task] = {}

    def _emit(self, task_id: str, event: str, agent: str | None = None, data: dict | None = None):
        self.seqs[task_id] = self.seqs.get(task_id, 0) + 1
        ev = AgentEvent(event=event, task_id=task_id, agent=agent,
                        seq=self.seqs[task_id], data=data or {})
        self.queues[task_id].put_nowait(ev)

    def start(self, req: StartTaskRequest):
        if req.task_id in self.jobs and not self.jobs[req.task_id].done():
            raise ValueError(f"task {req.task_id} already running")
        self.queues.setdefault(req.task_id, asyncio.Queue())
        payload = {"task_id": req.task_id, "requirement": req.requirement,
                   "auto_mode": req.auto_mode, "iteration_count": 0,
                   "role_models": req.role_models, "role_prompts": req.role_prompts}
        self.jobs[req.task_id] = asyncio.create_task(self._run(req.task_id, payload))

    def resume(self, task_id: str, decision: str, comment: str):
        if task_id not in self.queues:
            raise KeyError(task_id)
        cmd = Command(resume={"decision": decision, "comment": comment})
        self.jobs[task_id] = asyncio.create_task(self._run(task_id, cmd))

    def cancel(self, task_id: str):
        job = self.jobs.get(task_id)
        if job and not job.done():
            job.cancel()
            self._emit(task_id, "task_failed", data={"error": "canceled"})

    async def stream(self, task_id: str):
        queue = self.queues.setdefault(task_id, asyncio.Queue())
        while True:
            ev = await queue.get()
            yield ev
            if ev.event in ("task_done", "task_failed"):
                return

    def _handle_node_end(self, task_id: str, node: str, out: dict):
        out = out or {}
        content = ""
        if node in _ARTIFACTS:
            field, fname, ftype = _ARTIFACTS[node]
            content = out.get(field, "")
            if content:
                write_files(task_id, [{"path": fname, "content": content}])
                self._emit(task_id, "artifact_created", agent=node,
                           data={"name": fname, "type": ftype, "path": fname})
        if node == "coder":
            for f in out.get("code_files", []):
                self._emit(task_id, "artifact_created", agent=node,
                           data={"name": f["path"], "type": "code", "path": f["path"]})
            content = f"生成了 {len(out.get('code_files', []))} 个代码文件"
        if node == "reviewer":
            content = out.get("review_comments", "")
        if content:
            self._emit(task_id, "agent_message", agent=node,
                       data={"delta": content, "content": content})
        self._emit(task_id, "node_end", agent=node, data={"node": node})

    async def _run(self, task_id: str, payload):
        config = {"configurable": {"thread_id": task_id}}
        try:
            interrupted = False
            async for update in self.graph.astream(payload, config, stream_mode="updates"):
                for node, out in update.items():
                    if node == "__interrupt__":
                        interrupted = True
                        self._emit(task_id, "interrupt", data=dict(out[0].value))
                    else:
                        self._handle_node_end(task_id, node, out)
            if not interrupted:
                state = self.graph.get_state(config).values
                self._emit(task_id, "task_done",
                           data={"review_passed": state.get("review_passed", False),
                                 "iteration_count": state.get("iteration_count", 0)})
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001 —— 设计文档第5节：兜底转 task_failed
            logger.exception("task %s failed", task_id)
            self._emit(task_id, "task_failed", data={"error": str(e)})
