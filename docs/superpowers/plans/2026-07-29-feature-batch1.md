# 功能增强路线图与批次1实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## 总路线图（用户已确认全部要做，按此顺序分批）

| 批次 | 内容 | 状态 |
|---|---|---|
| **B1 实用套餐** | 失败断点重试 / 产物在线预览 / 任务zip打包下载 / Token消耗统计 | 本计划 |
| B2 多轮迭代协作 | 完成后继续对话增量修改 / 驳回定向回退 / 需求模板库 | 待规划 |
| B3 Git集成 | 任务完成自动 push 新分支（可选开PR） | 待规划 |
| B4 Agent能力增强 | Docker沙箱 → 自定义角色 → RAG知识库 → 多模型对比（按此序） | 待规划 |

---

# 批次1（B1）实现计划

**Goal:** 断点重试、产物预览、zip 打包、Token 统计四个功能端到端落地。

**架构决策：**
- **Token 统计不改数据库**：agent 在 GraphState 累计 `input_tokens/output_tokens`（读 LangChain `usage_metadata`），随 `node_end`/`task_done` 事件下发累计值；事件本就落库，前端从事件流取最新累计值展示。零迁移成本。
- **断点重试**：LangGraph 对失败任务用 `astream(None, config)` 从最近 checkpoint 续跑；agent 新增 `/retry` 端点复用 TaskManager 提交逻辑；server 新增 `POST /api/tasks/{id}/retry`（仅 failed 可调）并重新 startRelay。
- **预览/zip**：server 读 workspace 文件（复用 download 的防逃逸检查）；zip 用 `java.util.zip` 打整个 `T{id}` 目录。

### Task B1-1: agent — token 用量累计 + 事件下发（TDD）
- GraphState 增 `input_tokens/output_tokens`；`nodes._call` 改为返回 `(content, usage)`，各节点累计后写回 state
- runner `_handle_node_end` 在 `node_end` data 中附 `input_tokens/output_tokens`（州累计值）；`task_done` data 同样附上
- FakeLLM 无 usage → 取 0 不报错；conftest 增可选 usage 的 FakeLLM
- 测试：节点累计、happy path 后 task_done 事件含累计值

### Task B1-2: agent — 失败重试端点（TDD)
- `TaskManager.retry(task_id)`：提交 `None` payload 从 checkpoint 续跑（任务在跑则 409）
- `POST /agent/tasks/{id}/retry`（鉴权同其余端点）
- 测试：首次运行 LLM 抛错 → task_failed；换好 LLM 后 retry → task_done（FakeLLM 可控故障）

### Task B1-3: server — retry / 产物content / zip 三端点（TDD）
- `AgentClient.retry(taskId)` + Http 实现；`TaskService.retry`：仅 failed → 调 agent → running + relay.startRelay
- `GET /api/artifacts/{id}/content`：文本返回（复用防逃逸；>1MB 拒绝 413）
- `GET /api/tasks/{id}/artifacts/zip`：打包 workspace/T{id} 整目录 attachment
- 测试：retry 状态机（failed→running、running 调用 409）、content 正常/逃逸 400、zip 响应头与非空字节

### Task B1-4: web — 四个功能的界面（关键逻辑 TDD）
- taskEvents store：`tokenUsage` getter 从事件流取最新累计（含补拉场景）——Vitest
- TaskView：失败态"断点重试"按钮；头部 meta 加"消耗 N tokens"；产物行"预览"（抽屉：.md 走 renderMd，其他 `<pre>`）；产物卡头"打包下载"
- 工作台统计卡"已完成"旁不动（token 汇总留 B2 后评估）

### Task B1-5: 回归 + 提交 + 审查修复
- agent pytest 全绿、server mvn test 全绿、web vitest+build 全绿；每任务独立 commit

## 自审
- Token 方案不动 schema，事件协议向后兼容（data 加字段）；retry 复用 checkpoint 语义与设计文档5节"从断点重试"一致；zip/content 沿用既有防逃逸模式。
