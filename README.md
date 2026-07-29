# 多Agent软件开发协同平台

团队内部工具：提交一句话需求，五个 AI Agent（产品经理→架构师→开发→测试→审查）按软件开发
SOP 协作产出 PRD、设计文档、代码与测试报告，关键节点人工审批介入。

```
┌─────────────┐   REST / SSE   ┌──────────────────┐  HTTP / SSE  ┌───────────────────┐
│  Vue3 前端   │ ─────────────> │ SpringBoot 业务后端 │ ──────────> │ FastAPI+LangGraph  │
│  (web/)     │                │   (server/)       │             │  Agent服务 (agent/) │
└─────────────┘                └──────────────────┘             └───────────────────┘
                                │        │                        │
                             本地MySQL   (Redis后续)            LiteLLM 网关 (llm-gateway/)
                            (业务数据)                        → 通义 / DeepSeek / 智谱 / 第三方Key
```

| 模块 | 技术栈 | 说明文档 |
|---|---|---|
| [agent/](agent/README.md) | Python + FastAPI + LangGraph | 五角色编排、人审门、代码沙箱 |
| [server/](server/README.md) | SpringBoot 3 + MyBatis-Plus + Sa-Token | 用户/任务管理、SSE中继落库 |
| [web/](web/README.md) | Vue 3 + Vite + Element Plus | 任务工作台、审批、产物下载 |
| [llm-gateway/](llm-gateway/README.md) | LiteLLM（纯配置） | 多模型/多Key统一OpenAI兼容接入 |

设计文档：`docs/superpowers/specs/`，实现计划：`docs/superpowers/plans/`。

## 快速开始

前置依赖：JDK 17 + Maven、Python 3.11+、Node 18+、本地 MySQL 8（默认 root/root，可用
`DB_USER/DB_PASSWORD` 环境变量覆盖）。

```powershell
# 1. 配置模型 Key
cd llm-gateway; Copy-Item .env.example .env    # 编辑 .env 填入至少一个模型的 Key
pip install "litellm[proxy]"

# 2. 安装 agent 依赖
cd ..\agent; python -m venv .venv; .venv\Scripts\pip install -r requirements.txt

# 3. 安装前端依赖
cd ..\web; npm install

# 4. 一键启动四个服务（或按各模块 README 手动启动）
cd ..; powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1
```

浏览器访问 **http://localhost:5173**，默认账号 **admin / admin123**。

首次使用：系统管理 → 模型管理，登记网关里的模型名（如 `deepseek-v3`）→ 角色配置里给各角色
选默认模型 → 回到项目页发起任务。

## 端到端冒烟

1. 网关验证：`curl http://localhost:4000/v1/models -H "Authorization: Bearer sk-magent-local"`
2. Agent 直连冒烟（绕过 server）：`cd agent; .venv\Scripts\python smoke_test.py`
3. 全链路：前端建项目 → 发起任务"写一个Python计算器函数" → 观察五角色事件流 → PRD 审批 →
   设计审批 → 最终验收 → 下载产物

## 安全 · 部署前必改清单 ⚠️

仓库内的默认值仅供**本机单人试用**。一旦部署到公网或与他人共享网络，务必先改下列各项，否则同网段的人可能盗用你的模型额度、登录后台。

| 项 | 位置 | 仓库默认值 | 改法 |
|---|---|---|---|
| 内网令牌 | `server/.env` 与 `agent/.env` | `change-me` | 生成随机值，两处保持一致（见下） |
| AES 密钥 | `server/.env` | `0123456789abcdef` | 16 字节随机串 |
| 管理员密码 | 首次启动种子 | `admin/admin123` | 登录后立即改；或改 `DataInitializer` |
| 网关密钥 | `llm-gateway/.env` | `sk-magent-local` | 随机串；`agent/.env` 的 `AGENT_LLM_API_KEY` 同步 |
| MySQL 密码 | `server/.env` | `root` | 你的真实库口令 |

生成随机值并写入本地 `.env`（不入库）：

```powershell
# 复制模板
cd server; Copy-Item .env.example .env
# 生成令牌 / AES 密钥
python -c "import secrets;print('magent_'+secrets.token_urlsafe(32))"   # 填 INTERNAL_TOKEN（server 与 agent 一致）
python -c "import secrets,string;print(''.join(secrets.choice(string.ascii_letters+string.digits) for _ in range(16)))"  # 填 AES_KEY
```

**网络暴露面**：网关(4000) 与 Agent(8001) 已配置为**仅监听 `127.0.0.1`**（`start-gateway.ps1` / `start-all.ps1`），不对局域网开放。若确需跨机访问，请自行加鉴权/防火墙，勿直接改回 `0.0.0.0`。

所有 `.env` 与 `*.pem/*.key` 等密钥文件均已被 `.gitignore` 排除，不会入库。

## 生产部署注意

- Agent 服务设置 `AGENT_MYSQL_DSN` 启用 checkpoint 持久化（断点续跑）
- 反向代理（Nginx）统一入口，仅暴露前端与 server；网关/Agent 留在内网
- 上游中转站 Key 若曾在聊天/截图中出现过，建议到中转站后台重置
