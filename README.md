# 多Agent软件开发协同平台

团队内部工具：提交一句话需求，五个 AI Agent（产品经理→架构师→开发→测试→审查）按软件开发
SOP 协作产出 PRD、设计文档、代码与测试报告，关键节点人工审批介入。

```
┌─────────────┐   REST / SSE   ┌──────────────────┐  HTTP / SSE  ┌───────────────────┐
│  Vue3 前端   │ ─────────────> │ SpringBoot 业务后端 │ ──────────> │ FastAPI+LangGraph  │
│  (web/)     │                │   (server/)       │             │  Agent服务 (agent/) │
└─────────────┘                └──────────────────┘             └───────────────────┘
                                │        │                        │
                             本地MySQL   (Redis后续)            LiteLLM 网关 (llm-gateway/)
                            (业务数据)                        → 通义 / DeepSeek / 智谱 / 第三方Key
```

| 模块 | 技术栈 | 说明文档 |
|---|---|---|
| [agent/](agent/README.md) | Python + FastAPI + LangGraph | 五角色编排、人审门、代码沙箱 |
| [server/](server/README.md) | SpringBoot 3 + MyBatis-Plus + Sa-Token | 用户/任务管理、SSE中继落库 |
| [web/](web/README.md) | Vue 3 + Vite + Element Plus | 任务工作台、审批、产物下载 |
| [llm-gateway/](llm-gateway/README.md) | LiteLLM（纯配置） | 多模型/多Key统一OpenAI兼容接入 |

设计文档：`docs/superpowers/specs/`，实现计划：`docs/superpowers/plans/`。

## 快速开始

前置依赖：JDK 17 + Maven、Python 3.11+、Node 18+、本地 MySQL 8（默认 root/root，可用
`DB_USER/DB_PASSWORD` 环境变量覆盖）。

```powershell
# 1. 配置模型 Key
cd llm-gateway; Copy-Item .env.example .env    # 编辑 .env 填入至少一个模型的 Key
pip install "litellm[proxy]"

# 2. 安装 agent 依赖
cd ..\agent; python -m venv .venv; .venv\Scripts\pip install -r requirements.txt

# 3. 安装前端依赖
cd ..\web; npm install

# 4. 一键启动四个服务（或按各模块 README 手动启动）
cd ..; powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1
```

浏览器访问 **http://localhost:5173**，默认账号 **admin / admin123**。

首次使用：系统管理 → 模型管理，登记网关里的模型名（如 `deepseek-v3`）→ 角色配置里给各角色
选默认模型 → 回到项目页发起任务。

## 端到端冒烟

1. 网关验证：`curl http://localhost:4000/v1/models -H "Authorization: Bearer sk-magent-local"`
2. Agent 直连冒烟（绕过 server）：`cd agent; .venv\Scripts\python smoke_test.py`
3. 全链路：前端建项目 → 发起任务"写一个Python计算器函数" → 观察五角色事件流 → PRD 审批 →
   设计审批 → 最终验收 → 下载产物

## 生产部署注意

- 必改：`INTERNAL_TOKEN`（server 与 agent 一致）、`AES_KEY`、`LITELLM_MASTER_KEY`、admin 密码
- Agent 服务设置 `AGENT_MYSQL_DSN` 启用 checkpoint 持久化（断点续跑）
- 所有 `.env` 与密钥文件不入 git（已在 .gitignore）
