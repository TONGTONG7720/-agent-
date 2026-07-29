import asyncio
import logging
import queue
import threading

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

_POLL_SECONDS = 0.05


class TaskManager:
    """任务运行器：启动图执行、事件队列、resume/cancel。

    图执行跑在自有的常驻事件循环线程上，事件队列用线程安全的 queue.Queue，
    与 Web 服务器的事件循环解耦（TestClient 每请求独立循环、多 worker 场景下依然正确）。
    """

    def __init__(self, graph):
        self.graph = graph
        self.queues: dict[str, queue.Queue] = {}
        self.seqs: dict[str, int] = {}
        self.jobs: dict[str, object] = {}     # concurrent.futures.Future
        self._seq_lock = threading.Lock()     # cancel() 与图执行线程可能并发 _emit
        self._loop = asyncio.new_event_loop()
        threading.Thread(target=self._loop.run_forever,
                         daemon=True, name="task-manager-loop").start()

    def _emit(self, task_id: str, event: str, agent: str | None = None, data: dict | None = None):
        with self._seq_lock:
            self.seqs[task_id] = self.seqs.get(task_id, 0) + 1
            seq = self.seqs[task_id]
        ev = AgentEvent(event=event, task_id=task_id, agent=agent,
                        seq=seq, data=data or {})
        self.queues[task_id].put(ev)

    def _submit(self, task_id: str, payload):
        self.jobs[task_id] = asyncio.run_coroutine_threadsafe(
            self._run(task_id, payload), self._loop)

    def start(self, req: StartTaskRequest):
        job = self.jobs.get(req.task_id)
        if job and not job.done():
            raise ValueError(f"task {req.task_id} already running")
        self.queues.setdefault(req.task_id, queue.Queue())
        payload = {"task_id": req.task_id, "requirement": req.requirement,
                   "auto_mode": req.auto_mode, "iteration_count": 0,
                   "role_models": req.role_models, "role_prompts": req.role_prompts}
        self._submit(req.task_id, payload)

    def resume(self, task_id: str, decision: str, comment: str):
        if task_id not in self.queues:
            raise KeyError(task_id)
        self._submit(task_id, Command(resume={"decision": decision, "comment": comment}))

    def retry(self, task_id: str, after_seq: int | None = None):
        """失败任务从最近 checkpoint 续跑（图输入 None = resume）。

        after_seq：由 server 传入已落库的最大序号，保证重试后事件 seq 不回退
        （server 端 (task_id, seq) 唯一键会静默丢弃重号事件）。
        """
        config = {"configurable": {"thread_id": task_id}}
        snapshot = self.graph.get_state(config)
        if not snapshot.values:
            raise KeyError(task_id)
        job = self.jobs.get(task_id)
        if job and not job.done():
            raise ValueError(f"task {task_id} already running")
        self.queues.setdefault(task_id, queue.Queue())
        if after_seq is not None:
            with self._seq_lock:
                self.seqs[task_id] = max(self.seqs.get(task_id, 0), after_seq)
        self._submit(task_id, None)

    def cancel(self, task_id: str):
        job = self.jobs.get(task_id)
        if job and not job.done():
            job.cancel()
            self._emit(task_id, "task_failed", data={"error": "canceled"})

    async def stream(self, task_id: str):
        q = self.queues.setdefault(task_id, queue.Queue())
        while True:
            try:
                ev = q.get_nowait()
            except queue.Empty:
                await asyncio.sleep(_POLL_SECONDS)
                continue
            yield ev
            if ev.event in ("task_done", "task_failed"):
                self._cleanup(task_id)
                return

    def _cleanup(self, task_id: str):
        """终态事件被消费后释放 per-task 资源，避免长期运行无界增长。

        注意：seqs 故意保留（单 int 占用可忽略），retry 需延续序号避免与已落库事件撞号；
        也不能在客户端断线时清理队列（任务可能还在跑）。
        """
        self.queues.pop(task_id, None)
        self.jobs.pop(task_id, None)

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
        end_data = {"node": node}
        if "input_tokens" in out:
            end_data["input_tokens"] = out["input_tokens"]
            end_data["output_tokens"] = out.get("output_tokens", 0)
        self._emit(task_id, "node_end", agent=node, data=end_data)

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
                                 "iteration_count": state.get("iteration_count", 0),
                                 "input_tokens": state.get("input_tokens", 0),
                                 "output_tokens": state.get("output_tokens", 0)})
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001 —— 设计文档第5节：兜底转 task_failed
            logger.exception("task %s failed", task_id)
            self._emit(task_id, "task_failed", data={"error": str(e)})
