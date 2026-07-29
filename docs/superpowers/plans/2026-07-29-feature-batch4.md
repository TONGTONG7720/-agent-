# 批次4（B4）实现计划：Agent 能力增强（四阶段）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## 阶段总览（按序实施，每阶段独立可交付）

| 阶段 | 内容 | 状态 |
|---|---|---|
| B4-1 Docker 沙箱 | 测试执行隔离到容器（断网/限内存CPU），配置驱动可回退 subprocess | 本次 |
| B4-2 自定义角色 | 平台可增删角色、调整流水线顺序（动态建图） | 待做 |
| B4-3 RAG 知识库 | 上传团队规范/代码，Agent 检索参考 | 待做 |
| B4-4 多模型对比 | 同一需求两模型并跑对比 | 待做 |

## B4-1 Docker 沙箱设计

- **配置驱动**：`AGENT_SANDBOX_MODE=subprocess(默认)|docker`；镜像/内存/CPU 可配
  （用户机器 Docker CLI 29.6.1 已装但 daemon 未必常开——默认模式不依赖 Docker）
- **容器命令**：`docker run --rm --network none -m {mem} --cpus {cpus} -v {taskDir}:/work -w /work {image} python -m pytest -v --tb=short`
  —— 断网杜绝数据外流/下包，资源限额防失控
- **镜像**：`python:3.11-slim` 无 pytest 且容器断网无法 pip → 提供 `agent/sandbox.Dockerfile`
  （FROM python:3.11-slim + pip install pytest），默认镜像名 `magent-sandbox:latest`，README 给一次性构建命令
- **实现**：sandbox.py 抽公共 `_exec`（限时+截断）；`build_docker_cmd(cwd)` 纯函数；`run_pytest` 按模式分派
- **测试**：命令构造断言（断网/挂载/镜像/限额）；分派逻辑 mock subprocess 断言实际调用（不需要真 Docker）；docker 不可用时报错信息清晰

## 任务
- B4-1a agent：配置项 + build_docker_cmd + 分派 ——TDD
- B4-1b：sandbox.Dockerfile + README 说明 + 回归提交
