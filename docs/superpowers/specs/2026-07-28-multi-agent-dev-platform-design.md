# 多Agent软件开发协同平台 — 设计文档

- 日期：2026-07-28
- 状态：已评审通过（方案A）
- 定位：团队内部工具（需登录/权限，中低并发）

## 1. 项目概述

一个团队内部使用的多Agent软件开发协同平台：用户提交一句话需求，系统内多个 AI Agent
分别扮演产品经理、架构师、开发、测试、审查角色，按软件开发 SOP 协作产出 PRD、设计文档、
代码与测试报告，关键节点支持人工审批介入。

### 1.1 技术选型（方案A：LangGraph 自建编排）

| 层 | 选型 |
|---|---|
| 前端 | Vue 3 + Vite + TypeScript + Element Plus + Pinia + Vue Router |
| 业务后端 | SpringBoot 3.x (Java 17) + MyBatis-Plus + Sa-Token + Redis |
| Agent 服务 | Python 3.11+ + FastAPI + LangGraph + langchain-openai |
| 模型网关 | LiteLLM（统一 OpenAI 兼容格式，接入通义/DeepSeek/智谱/第三方运营商 Key） |
| 数据库 | 本地 MySQL（连接信息可配置，默认 localhost:3306） |

选型理由：
- 软件开发协同本质是有向流程（需求→设计→编码→测试→审查，可回退），LangGraph
  状态图 + 条件边天然匹配，且支持 checkpoint 断点续跑与 interrupt 人工介入。
- LiteLLM 网关独立部署，Java/Python 侧只面向 OpenAI 兼容接口编程，换模型/换
  运营商 Key 仅改网关配置，零代码改动。
- 借鉴 MetaGPT 的角色 Prompt 与 SOP 设计，但流程自主可控。

## 2. 整体架构与模块划分

```
┌─────────────┐   REST / SSE   ┌──────────────────┐  HTTP / SSE  ┌───────────────────┐
│  Vue3 前端   │ ─────────────> │ SpringBoot 业务后端 │ ──────────> │ FastAPI+LangGraph  │
│  (web/)     │                │   (server/)       │             │  Agent服务 (agent/) │
└─────────────┘                └──────────────────┘             └───────────────────┘
                                │        │                        │
                             本地MySQL   Redis                  LiteLLM 网关 (llm-gateway/)
                            (业务数据)  (SSE会话/缓存)          → 通义 / DeepSeek / 智谱 / 第三方Key
```

### 2.1 `web/` — Vue3 前端

- 核心页面：登录页、项目列表、任务工作台（提交需求 → 实时查看多Agent协作过程流 →
  审批卡片 → 查看/下载产物）、Agent 角色配置页、模型/Key 管理页。
- 只与 SpringBoot 通信，不直连 Agent 服务。

### 2.2 `server/` — SpringBoot 业务后端

- 职责：用户/登录/权限（Sa-Token）、项目与任务 CRUD、任务生命周期管理
  （pending → running → waiting_review → done/failed/canceled）、SSE 事件转发与落库、
  产物存储与下载、模型 Key 的 AES 加密存储与脱敏返回。
- 不包含任何 AI 逻辑，是系统的"管理面"。

### 2.3 `agent/` — Python Agent 服务

- 职责：LangGraph 多Agent状态图编排（角色节点、条件流转、interrupt 人审点）、
  工具调用（文件读写、测试执行）、通过 SSE 向 SpringBoot 推送执行事件。
- LangGraph checkpoint 持久化到 MySQL（独立 schema `magent_graph`），服务重启可恢复任务。

### 2.4 `llm-gateway/` — LiteLLM 网关

- Docker 或 pip 启动，纯配置无代码；所有模型在 `litellm-config.yaml` 注册。
- 能力：按模型名路由、Key 故障自动切换、token 用量记录。

### 2.5 数据库规划（本地 MySQL）

- `magent_biz`：SpringBoot 业务库（用户、项目、任务、事件、产物、模型配置等）。
- `magent_graph`：LangGraph checkpointer 自动建表，不手工维护。

## 3. 多Agent角色设计与协作流程

### 3.1 五个内置角色（Prompt 可在前端配置页调整）

| 角色 | 职责 | 输入 → 输出 |
|---|---|---|
| 产品经理 PM | 一句话需求扩写为结构化 PRD | 用户需求 → PRD（功能列表、验收标准） |
| 架构师 Architect | 技术方案与任务拆解 | PRD → 设计文档（模块划分、接口定义、文件清单） |
| 开发 Coder | 按文件清单逐个实现代码 | 设计文档 → 代码文件集 |
| 测试 Tester | 编写并运行测试，输出报告 | 代码 → 测试代码 + 执行结果 |
| 审查 Reviewer | 审查质量与需求符合度 | 代码 + 测试报告 → 审查结论（放行/修改意见） |

### 3.2 LangGraph 状态图

```
用户需求
   │
   ▼
  PM ──> 【人工确认PRD】──> 架构师 ──> 【人工确认设计】──> Coder ──> Tester
   ▲          │(驳回)                    │(驳回)              ▲         │
   └──────────┘          ◄──────────────┘                    │         ▼
                                                             │      Reviewer
                                                    (修改意见,│         │
                                                     最多3轮) └─────────┤(需修改)
                                                                       │(通过)
                                                                       ▼
                                                             【人工验收】──> 完成
```

### 3.3 关键设计点

1. **共享状态 GraphState**：单一 TypedDict 贯穿全图，字段包括
   `requirement / prd / design_doc / code_files / test_report / review_comments /
   iteration_count / messages`；每个节点只读写自己关心的字段。
2. **三个人工介入点（LangGraph interrupt）**：PRD 确认、设计确认、最终验收。触发时任务
   状态变为 waiting_review，用户在前端"通过/驳回+意见"，SpringBoot 调 resume 接口从
   checkpoint 继续。人审点可在任务创建时按需开关（auto_mode 全自动）。
3. **修复循环上限**：Reviewer → Coder 返工最多 3 轮（iteration_count 控制），超限强制进入
   人工验收并附"未通过审查"标记，避免死循环烧 token。
4. **Tester 代码执行（MVP）**：Agent 服务本机隔离临时目录 subprocess 执行，限时 120s、
   输出截断 64KB、命令白名单；仅支持 Python/Node 项目测试。Docker 沙箱为后续增强项。
5. **角色模型可配**：每个角色可独立指定模型（如 Coder 用 DeepSeek-V3、PM 用通义 Plus），
   存于 agent_role_config 表，任务启动时由 SpringBoot 传给 Agent 服务。
6. **产物管理**：每任务一个工作目录 `workspace/{task_id}/`，PRD/设计文档/代码/测试报告
   落盘并登记数据库，前端可在线预览与打包下载。

## 4. 接口设计与数据流

### 4.1 SSE 事件协议（Agent服务 → SpringBoot → 前端，统一 JSON 透传）

```json
{
  "event": "agent_message",
  "task_id": "T20260728001",
  "agent": "coder",
  "seq": 42,
  "data": {},
  "ts": 1785000000000
}
```

| event | 含义 | data 内容 |
|---|---|---|
| node_start / node_end | 角色开始/结束工作 | 节点名 |
| agent_message | 角色流式输出（打字机效果） | {delta, content} |
| artifact_created | 产物生成 | {name, type, path}（artifact_id 由 SpringBoot 落库时生成并在转发前端时补齐） |
| interrupt | 到达人审点，任务挂起 | {gate, question, payload}（checkpoint 由 LangGraph 内部管理，resume 仅需 task_id） |
| task_done / task_failed | 终态 | 结果摘要 / 错误信息 |

事件由 SpringBoot 消费时写入 `task_event` 表；前端刷新/断线后按 seq 补拉历史，
SSE 只负责增量；seq 单调递增用于排序与去重。

### 4.2 核心 API

前端 ↔ SpringBoot（`/api/**`，Sa-Token 鉴权）：

- `POST /api/auth/login`、`/api/users` — 登录与用户管理
- `GET/POST /api/projects` — 项目 CRUD
- `POST /api/tasks` — 创建任务 `{project_id, requirement, role_model_map, auto_mode}`
- `GET /api/tasks/{id}/events?after_seq=` — 补拉历史事件
- `GET /api/tasks/{id}/stream` — SSE 订阅实时事件
- `POST /api/tasks/{id}/approve` — 人审决策 `{decision: pass|reject, comment}`
- `GET /api/tasks/{id}/artifacts`、`GET /api/artifacts/{id}/download` — 产物预览/下载
- `GET/POST /api/models` — 模型与 Key 管理（Key AES 加密存储，返回脱敏）

SpringBoot ↔ Agent 服务（内网，Header `X-Internal-Token` 共享密钥）：

- `POST /agent/tasks` — 启动任务（需求、角色模型映射、人审开关）
- `GET /agent/tasks/{id}/stream` — SSE 事件流（SpringBoot 消费后转发+落库）
- `POST /agent/tasks/{id}/resume` — 人审后从 checkpoint 恢复
- `POST /agent/tasks/{id}/cancel` — 取消任务

### 4.3 核心表结构（magent_biz）

| 表 | 关键字段 |
|---|---|
| sys_user | id, username, password(bcrypt), role(admin/member) |
| project | id, name, owner_id |
| task | id, project_id, requirement, status(pending/running/waiting_review/done/failed/canceled), auto_mode, current_node, created_by |
| task_event | id, task_id, seq, event, agent, data(json) — 事件溯源 |
| artifact | id, task_id, name, type(prd/design/code/test_report), path, version |
| llm_model | id, name, litellm_model_name, enabled — 对应网关注册的模型 |
| agent_role_config | id, role(pm/architect/coder/tester/reviewer), system_prompt, default_model_id |

### 4.4 一次任务的完整数据流

1. 前端 `POST /api/tasks` → SpringBoot 建 task（pending），组装角色配置 →
   调 Agent 服务 `POST /agent/tasks` → running。
2. SpringBoot 订阅 Agent SSE → 每条事件落 task_event 表 + 转发前端 SSE。
3. 遇 interrupt 事件 → task.status=waiting_review → 前端弹出审批卡片。
4. 用户审批 → SpringBoot 调 resume → 继续执行。
5. task_done → status=done，产物登记完毕，前端可下载。

## 5. 错误处理

分层兜底原则：每层只处理自己层面的故障，向上抛统一格式错误。

| 故障场景 | 处理策略 |
|---|---|
| LLM 调用失败（超时/限流/Key失效） | 第一道：LiteLLM 网关内自动重试 + 同模型多 Key 故障切换；第二道：Agent 节点内指数退避重试 2 次；仍失败 → task_failed 事件，checkpoint 保留，前端提供"从断点重试"（复用 resume） |
| Agent 输出格式不合规 | 节点内 Pydantic 结构化校验，失败带错误信息让模型自我修正，最多 2 次；仍失败按节点失败处理 |
| Agent 服务宕机/重启 | 状态与 checkpoint 均在 MySQL；SpringBoot 定时对账：running 但 Agent 侧无此任务 → 标记 failed 可断点重试 |
| SSE 断线 | 前端自动重连，after_seq 补拉缺失事件，seq 去重防重复渲染 |
| 测试代码执行失控 | subprocess 限时 120s、输出截断 64KB、工作目录隔离、命令白名单校验 |
| Token 消耗失控 | 单任务累计 token 上限（可配，默认 50 万），LiteLLM 用量回传，超限强制挂起转人工决定 |

统一错误码：SpringBoot 对前端返回 `{code, message, detail}`；Agent 服务对 SpringBoot
同格式，SpringBoot 透传时附加上下文。

## 6. 测试策略

| 层 | 测试内容 | 工具 |
|---|---|---|
| Agent 服务单测 | 各节点输入输出契约（mock LLM）；状态图流转（驳回回退、3轮上限、interrupt 触发） | pytest + LangGraph 单节点 invoke |
| SpringBoot 单测 | 任务状态机流转、事件落库与 seq 连续性、Key 加密脱敏 | JUnit 5 + Mockito + H2 |
| 集成测试 | Spring↔Agent 全链路（stub LLM）：建任务→事件流→interrupt→resume→done | Testcontainers(MySQL) + pytest httpx |
| 冒烟测试 | 真实模型跑通最小需求（如"写一个计算器函数"），端到端验证 | 手动脚本，接真实网关 |
| 前端 | 事件流渲染、断线补拉、审批卡片 | Vitest（MVP 只覆盖 SSE 处理逻辑） |

测试基调：LLM 输出不可断言精确内容，单测/集成测全部 mock 模型层，只验证编排逻辑
与契约；真实模型质量靠冒烟测试 + 人审点把关。

## 7. MVP 范围与后续增强

MVP 包含：

- 四模块（web / server / agent / llm-gateway）全部搭建并跑通端到端流程
- 五角色固定流水线 + 3 个可开关人审点 + 3 轮返工上限
- 用户登录/权限（admin/member 两级）、项目/任务管理、产物预览下载
- 模型与 Key 管理（网关配置 + 数据库登记）
- Python/Node 项目的测试执行（subprocess 隔离）

明确不在 MVP（后续增强）：

- Docker 沙箱代码执行
- 自定义角色/自定义流程编排（可视化画布）
- 多租户、计费、任务队列削峰
- Git 集成（Agent 直接提交代码到仓库）

## 8. 部署形态

- 开发/内部部署：本地 MySQL + Redis；`web` 静态资源由 Nginx 或 SpringBoot 托管；
  `server`、`agent`、`llm-gateway` 三进程独立启动（可 docker-compose 编排）。
- 配置约定：所有敏感配置（数据库、Redis、内部密钥、模型 Key）走环境变量或本地
  配置文件，不入库不入 git。
