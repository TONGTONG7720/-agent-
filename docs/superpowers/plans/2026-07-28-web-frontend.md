# Vue3 前端（web/）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现平台前端：登录、项目/任务管理、任务工作台（实时事件流 + 审批卡片 + 产物下载）、模型与角色配置管理。

**Architecture:** Vue 3 + Vite + TypeScript + Element Plus + Pinia + Vue Router。所有请求经 `/api` 走 Vite 代理到 SpringBoot(8080)；SSE 用原生 EventSource（token 走 `?satoken=` 查询参数，Sa-Token 默认支持）；**事件流的去重/排序/补拉合并逻辑抽成纯函数 store，用 Vitest 覆盖**（设计文档第 6 节：前端 MVP 只测 SSE 处理逻辑）。

**Tech Stack:** vue@3.4, vite@5, typescript@5, element-plus@2.7, pinia@2, vue-router@4, vitest@1

**对应设计文档：** 第 2.1、4.1(前端消费)、4.2(前端API)、5(SSE断线重连) 节

---

## 文件结构

```
web/
  package.json / vite.config.ts / tsconfig.json / index.html
  src/
    main.ts / App.vue
    router/index.ts          # 路由 + 登录守卫
    api/http.ts              # fetch 封装：satoken header、401跳登录、统一错误提示
    api/index.ts             # 各业务 API 函数
    stores/auth.ts           # 登录态（token/role 持久化 localStorage）
    stores/taskEvents.ts     # 核心：事件缓冲区（seq去重排序、补拉合并、SSE接入）
    views/LoginView.vue
    views/ProjectsView.vue   # 项目列表 + 建项目 + 建任务入口
    views/TaskView.vue       # 工作台：事件流渲染/审批卡片/产物下载/取消
    views/AdminView.vue      # 模型管理 + 角色配置（admin）
  tests/taskEvents.spec.ts   # Vitest：事件缓冲逻辑
  tests/http.spec.ts         # Vitest：http 封装 401/错误处理
```

## 任务列表

### Task 1: Vite 骨架 + 构建通过
- package.json（依赖如上，scripts: dev/build/test）、vite.config.ts（`/api` 代理→8080，含 SSE 代理配置）、tsconfig、index.html、main.ts、App.vue(router-view)、空路由
- 验收：`npm install` 成功，`npm run build` 通过 → commit

### Task 2: http 封装 + auth store（TDD）
- 失败测试 `tests/http.spec.ts`（mock fetch）：请求自动带 `satoken` header；HTTP 401 → 清空 token；`Result.code!=0` 抛错带 message
- 实现 `api/http.ts`（get/post/put，返回 `data` 字段）、`stores/auth.ts`（login/logout/token 持久化）
- 验收：vitest 绿 → commit

### Task 3: taskEvents store（TDD，核心）
- 失败测试 `tests/taskEvents.spec.ts`：
  1. `pushEvent` 按 seq 排序插入、重复 seq 忽略
  2. `mergeHistory`（补拉结果）与实时事件合并无重复
  3. `interrupt` 事件置 `pendingGate`；`task_done/task_failed` 置终态并清 pendingGate
  4. agent_message 按 agent 分组聚合出 `messages` 计算属性
- 实现 `stores/taskEvents.ts`：纯逻辑 + `connect(taskId)`（EventSource 接入、onerror 重连并用 maxSeq 补拉——重连逻辑不进单测）
- 验收：vitest 绿 → commit

### Task 4: 登录页 + 路由守卫
- LoginView（Element Plus 表单）、router 守卫（无 token → /login；/admin 需 admin 角色）
- 验收：build 通过 → commit

### Task 5: 项目与任务列表页
- ProjectsView：项目卡片列表、新建项目对话框、项目下任务列表（状态 tag 着色）、新建任务对话框（需求文本 + autoMode 开关）→ 跳转 TaskView
- 验收：build 通过 → commit

### Task 6: 任务工作台 TaskView
- 布局：左侧五角色步骤条（依据最新 node_end/current_node 高亮）；中间事件流（按 agent 分组卡片，agent_message 渲染 markdown 原文）；顶部状态栏（状态 tag + 取消按钮）
- 审批卡片：`pendingGate` 非空时弹出（gate 问题 + payload 预览 + 通过/驳回+意见）→ approve API
- 产物区：artifacts 列表 + 下载链接（`/api/artifacts/{id}/download?satoken=`）
- 进入页面：先 `GET events?afterSeq=0` 补历史再 connect SSE
- 验收：build 通过 → commit

### Task 7: AdminView（模型 + 角色配置）
- 模型表格（name/litellmModelName/enabled/apiKeyMasked）+ 新增对话框；角色配置表格（role/systemPrompt 编辑/defaultModelId 下拉选模型）
- 验收：build 通过 → commit

### Task 8: README + 全量验证
- `web/README.md`：启动方式（npm run dev，需 server 在 8080）、构建、测试
- 验收：`npm run test -- --run` + `npm run build` 全绿 → commit

## 自审记录
- 覆盖设计文档 2.1 全部页面（登录/项目/工作台/角色配置/模型Key管理）；4.1 前端消费（seq 去重排序补拉）→ Task 3；4.2 全部前端 API → Task 2/5/6/7；5 节 SSE 断线重连 → Task 3 connect。
- 测试范围遵循设计文档第 6 节：仅 SSE 处理逻辑 + http 封装做单测，视图层靠 build 类型检查 + 后续端到端冒烟（计划4）。
- 契约一致：事件 JSON 字段与 server SseRegistry.buildPayload 一致（event/task_id/agent/seq/data/ts）；状态枚举与 task 表一致。
