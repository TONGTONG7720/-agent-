# 批次2（B2）实现计划：多轮迭代协作

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ① 任务完成后可继续提出修改意见，Coder 在现有代码上增量修改并重走测试/审查/验收；② 人审驳回可定向回退到指定环节；③ 需求模板库一键填充。

## 架构决策

**迭代（iterate）——条件入口而非新图：**
- LangGraph 图入口改为条件入口：state 含 `iterate_feedback` → 直接进 `coder`，否则进 `pm`
- 对已完成线程再次 invoke 时新输入与 checkpoint 状态合并（prd/design/code 全保留），天然支持"基于现状改"
- TaskManager.iterate 注入 `{iterate_feedback, iteration_count:0, review_passed:False, review_comments:""}`（新一轮返工额度重置）
- Coder 收到 iterate_feedback 时：prompt 附现有代码全文 + "增量修改"指令，输出全部文件；跑完清空该字段
- 走完仍经 accept_gate 人审（auto_mode 则直通）

**定向回退（reject target）：**
- resume 决策体增加可选 `target`；门节点把它写入 `reject_target`
- 路由表：prd_gate 驳回→pm；design_gate 驳回→architect(默认)|pm；accept_gate 驳回→coder(默认)|architect|pm —— 同时修复现状 bug：accept_gate 驳回目前直接 END 什么都不做
- pm/architect/coder 运行时清空 human_feedback 与 reject_target；coder 需新增消费 human_feedback（终审驳回意见）

**模板库：** 前端内置常量模板（CRUD接口/爬虫/CLI工具/网页Demo），发起任务对话框一键填充，零后端

**前端事件流适配（关键坑）：** store.finished 一旦见到旧 task_done 便不再订阅 SSE，迭代/重试后新事件收不到——connect 增加 forceLive 参数由任务状态驱动；非终态事件把 finished 复位（新一轮开始）

## 任务
- B2-1 agent：条件入口 + iterate（TaskManager/端点/coder增量prompt）——TDD
- B2-2 agent：定向回退路由 + accept_gate 驳回修复 ——TDD
- B2-3 server：`POST /api/tasks/{id}/iterate {feedback}`（仅done）+ approve 透传 target ——TDD
- B2-4 web：迭代输入卡（done态）/驳回目标选择/模板库/connect(forceLive) ——store逻辑TDD
- B2-5 三端回归 + 提交
