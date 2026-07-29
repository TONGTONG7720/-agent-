# 批次4-2（B4-2）实现计划：自定义角色 / 自由编排流水线

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用户可任意增删 Agent 角色、调整流水线顺序、指定每个角色是否带人审门、指定哪个角色是"审查/可返工"环节；未自定义时行为与现状完全一致。

## 架构决策（加法式，低风险）
- **动态流水线引擎与现有硬编码图并存**：`build_pipeline_graph(spec, ...)` 按 spec 动态建图；`build_graph`（默认五角色）原样保留，现有 43 个 agent 测试零改动
- **流水线 = 有序步骤列表**（线性 SOP，符合软件开发流程；非任意分支 DAG）。每步 `{key,name,kind,gate,rework_target,system_prompt,model}`
  - `kind`: `analysis`(产文档) / `code`(产代码文件) / `test`(写测试+跑) / `review`(判 PASS/FAIL，失败回 rework_target，受 max_fix_rounds 限)
- **通用状态累加器 `documents`**：每步产出 `{key,name,content}` 追加，后续步骤读全部 documents 作上下文（取代写死的 prd/design_doc 字段）
- **入口/迭代/回退通用化**：iterate 进第一个 `code` 步；gate 驳回回被守护步或 reject_target；accept_gate 驳回回 code 入口
- **默认 spec 常量**再现当前五角色流程，作为自定义的起点

## 任务
- B4-2a agent：state.documents + `pipeline.py`(通用节点+动态建图) + DEFAULT_PIPELINE ——TDD（自定义增删角色/审查返工/gate 全覆盖），旧测试保持绿
- B4-2b agent：runner 支持按 spec 建图（TaskManager 接收可选 pipeline，缺省用默认图）；main 传参 ——TDD
- B4-2c server：`agent_role_config` 增列 kind/ord/enabled/gate/rework_target；流水线 CRUD（列表/新增/删除/重排/改配置）；create 时组装 spec 传 agent（仅当自定义）——TDD
- B4-2d web：流水线编辑器（角色卡上下移/增删/开关人审/编辑 kind 与 prompt）——store 逻辑 TDD
- B4-2e 三端回归 + 提交
