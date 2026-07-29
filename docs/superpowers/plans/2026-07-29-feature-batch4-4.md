# 批次4-4（B4-4）实现计划：多模型对比

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 同一需求用两个模型各跑一遍，并排对比产出、token 消耗、最终结论。

## 架构决策（最大化复用，零新表）
- **对比 = 两个并行任务**：server 新增 `compareTask`，用同一 requirement + 同一流水线/知识库，各角色模型分别强制为 A/B 两模型，`auto_mode=true`（跳过人审保证可比），返回两个 taskId
- **复用现有编排**：不改 agent（`role_models` 已支持逐角色指定）；把每个角色都指到目标模型即可
- **前端对比页**：`/compare/:idA/:idB` 两列并排。用**轮询式**读取（getTask + listEvents 直到终态）——避免 Pinia 单例 store 无法同时承载两任务的问题；各列展示：状态、token 消耗、Agent 消息流（Markdown）、产物
- 建对比入口放工作台"模型对比"对话框（选需求 + 模型 A/B）

## 任务
- B4-4a server：`TaskService.createComparison` + `POST /api/tasks/compare` + 强制模型覆盖 ——TDD（两任务、模型分别覆盖、auto_mode）
- B4-4b web：对比对话框 + CompareView 两列轮询 + 路由 + api ——关键组合逻辑可编译验证
- B4-4c：三端回归 + 提交
