import hmac

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse

from .config import settings
from .graph.builder import build_graph
from .llm import default_llm_factory
from .runner import TaskManager
from .sandbox import run_pytest
from .schemas import ResumeRequest, StartTaskRequest


def check_token(x_internal_token: str = Header(default="")):
    # 常量时间比较，避免时序侧信道
    if not hmac.compare_digest(x_internal_token.encode(), settings.internal_token.encode()):
        raise HTTPException(status_code=401, detail="invalid internal token")


def get_checkpointer():
    if settings.mysql_dsn:
        from langgraph.checkpoint.mysql.pymysql import PyMySQLSaver
        saver = PyMySQLSaver.from_conn_string(settings.mysql_dsn).__enter__()
        saver.setup()
        return saver
    from langgraph.checkpoint.memory import MemorySaver
    return MemorySaver()


def create_app(manager: TaskManager | None = None) -> FastAPI:
    app = FastAPI(title="Multi-Agent Dev Service")
    if manager is None:
        graph = build_graph(default_llm_factory, get_checkpointer(), run_pytest)
        manager = TaskManager(graph)
    app.state.manager = manager

    @app.post("/agent/tasks", dependencies=[Depends(check_token)])
    async def start_task(req: StartTaskRequest):
        try:
            manager.start(req)
        except ValueError as e:
            raise HTTPException(status_code=409, detail=str(e))
        return {"code": 0, "message": "started"}

    @app.get("/agent/tasks/{task_id}/stream", dependencies=[Depends(check_token)])
    async def stream(task_id: str):
        async def gen():
            async for ev in manager.stream(task_id):
                yield f"data:{ev.model_dump_json()}\n\n"
        return StreamingResponse(gen(), media_type="text/event-stream")

    @app.post("/agent/tasks/{task_id}/resume", dependencies=[Depends(check_token)])
    async def resume(task_id: str, req: ResumeRequest):
        try:
            manager.resume(task_id, req.decision, req.comment)
        except KeyError:
            raise HTTPException(status_code=404, detail="task not found")
        return {"code": 0, "message": "resumed"}

    @app.post("/agent/tasks/{task_id}/cancel", dependencies=[Depends(check_token)])
    async def cancel(task_id: str):
        manager.cancel(task_id)
        return {"code": 0, "message": "canceled"}

    return app


def app_factory() -> FastAPI:
    """uvicorn 启动入口: uvicorn app.main:app_factory --factory --port 8001"""
    return create_app()
