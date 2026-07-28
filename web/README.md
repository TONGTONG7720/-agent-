# web/ — 多Agent协同开发平台 · 前端

Vue 3 + Vite + TypeScript + Element Plus + Pinia。页面：登录、项目列表（建项目/发起任务）、
任务工作台（五角色流水线 + 实时事件流 + 审批卡片 + 产物下载）、系统管理（模型/角色配置，admin）。

## 启动（开发）

```powershell
npm install
npm run dev          # http://localhost:5173，/api 代理到 SpringBoot(8080)
```

前置：`server/` 已在 8080 端口运行（默认账号 admin/admin123）。

## 关键机制

- **SSE 实时事件**：进入工作台先 `GET /api/tasks/{id}/events?afterSeq=0` 补历史，
  再 EventSource 订阅 `/api/tasks/{id}/stream?satoken=`；断线 3s 后按 maxSeq 补拉缺口并重连；
  seq 去重排序保证事件不重不漏（逻辑在 `src/stores/taskEvents.ts`，有单测覆盖）
- **人审**：收到 `interrupt` 事件弹出审批卡片，通过/驳回调用 `/approve`
- **鉴权**：satoken 存 localStorage，请求自动带 header，401 自动清除并回登录页

## 测试与构建

```powershell
npm run test -- --run    # Vitest：http 封装 + 事件流 store（10 用例）
npm run build            # vue-tsc 类型检查 + vite 构建
```
