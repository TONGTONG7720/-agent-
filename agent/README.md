# agent/ — 多Agent协同开发平台 · Agent 服务

FastAPI + LangGraph 实现的多Agent编排服务：五个角色（PM → 架构师 → Coder → Tester → Reviewer）
按软件开发 SOP 协作，支持三道人审门（interrupt/resume）、返工循环（最多 3 轮）、
checkpoint 断点续跑。LLM 统一走 LiteLLM 网关的 OpenAI 兼容接口。

## 环境变量（前缀 `AGENT_`，也可写 `.env`）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AGENT_LLM_BASE_URL` | `http://localhost:4000` | LiteLLM 网关地址 |
| `AGENT_LLM_API_KEY` | `sk-litellm` | 网关 Key |
| `AGENT_INTERNAL_TOKEN` | `change-me` | 与 SpringBoot 共享的内网密钥（生产必须改） |
| `AGENT_MYSQL_DSN` | 空 | LangGraph checkpoint MySQL DSN，如 `mysql://user:pwd@localhost:3306/magent_graph`；空则用内存 |
| `AGENT_WORKSPACE_ROOT` | `./workspace` | 任务产物工作目录 |
| `AGENT_MAX_FIX_ROUNDS` | `3` | Reviewer→Coder 返工上限 |
| `AGENT_TEST_TIMEOUT_SECONDS` | `120` | 测试执行超时 |

## 启动

```powershell
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
.venv\Scripts\uvicorn app.main:app_factory --factory --host 0.0.0.0 --port 8001
```

## API（Header 需带 `X-Internal-Token`）

- `POST /agent/tasks` — 启动任务 `{task_id, requirement, auto_mode, role_models, role_prompts}`
- `GET /agent/tasks/{id}/stream` — SSE 事件流（`node_end/agent_message/artifact_created/interrupt/task_done/task_failed`）
- `POST /agent/tasks/{id}/resume` — 人审决策 `{decision: pass|reject, comment}`
- `POST /agent/tasks/{id}/cancel` — 取消任务

## 测试

```powershell
.venv\Scripts\python -m pytest -v        # 全部单测/集成测（mock LLM，离线可跑）
```

## 冒烟（需真实网关）

1. 启动 LiteLLM 网关（见 `llm-gateway/`），确认 `AGENT_LLM_BASE_URL` 指向它
2. 启动本服务（见上）
3. `.venv\Scripts\python smoke_test.py` — 观察五角色事件流直至 `task_done`
