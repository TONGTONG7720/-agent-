# 批次4-3（B4-3）实现计划：RAG 知识库

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 上传团队规范/已有代码到知识库；建任务时按需求自动检索相关片段，注入 Agent 上下文作参考。

## 架构决策（轻量、离线可测、零新依赖）
- **检索算法**：BM25-lite 关键词检索（纯 Java）。不引入向量库/embedding API（用户中转站 embedding 支持不确定、且会增重）。中文按单字、英文按词切分打分，足够"检索相关片段"
- **存储**：server MySQL 新表 `knowledge_doc`(id,name,content,created_at)；知识由 server 拥有，agent 保持无状态
- **注入时机**：`TaskService.create` 按 requirement 检索 top-K 片段（限总字数）→ 作为 `knowledge` 字符串放进 agent 启动载荷；与自定义流水线正交
- **注入位置**：agent 两套引擎统一在 `_call` 的 SystemMessage 追加"参考知识库"段（pipeline.py + nodes.py），所有角色可见
- **分块**：文档按 ~500 字切块，逐块打分，返回拼接的高分块

## 任务
- B4-3a server：knowledge_doc 实体/表 + KnowledgeService(分块+BM25检索) + KnowledgeController(上传/列表/删除) + create 注入 knowledge ——TDD
- B4-3b agent：StartTaskRequest.knowledge + 两引擎 _call 注入参考段 ——TDD
- B4-3c web：AdminView 知识库卡（上传名称+内容/列表/删除）
- B4-3d：三端回归 + 提交
