# 批次3（B3）实现计划：Git 集成

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 已完成任务一键把产物推送到用户 Git 仓库的新分支。

## 架构决策
- **git 操作放 agent 侧**（文件所在地），subprocess 调本机 git；在任务目录 `git init`（幂等）→ add/commit → `git push <url> HEAD:refs/heads/<branch>`（直推 URL，免 remote 管理；孤立历史推新分支合法）
- **零数据库迁移**：仓库地址/Token 由前端对话框传入（repoUrl 用 localStorage 记忆，Token 不落库不落盘，仅本次请求透传）；https 时 Token 注入 URL，file:// 本地路径直推（测试用）
- 分支名：`magent/{taskId}-{yyyyMMddHHmmss}`；重复推送生成新分支；nothing-to-commit 容忍（产物没变也允许推）
- commit 身份用 `-c user.name/user.email` 内联，避免依赖全局 git 配置；错误信息截断且**绝不回显含 Token 的 URL**
- **离线可测**：pytest 用本地 bare 仓库当 remote（file 路径 push 不走网络）

## 任务
- B3-1 agent：`gitops.push_task` + `POST /agent/tasks/{id}/push` ——TDD（bare 仓库验证分支/提交内容、空目录报错、token 注入不泄漏）
- B3-2 server：`AgentClient.push` + `TaskService.pushToGit`（仅 done）+ `POST /api/tasks/{id}/push` ——TDD
- B3-3 web：done 态"推送到Git"对话框（repoUrl 记忆 + token 密文输入）→ 成功展示分支名
- B3-4 三端回归 + 提交
