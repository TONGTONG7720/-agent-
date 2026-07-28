# server/ — 多Agent协同开发平台 · 业务后端

SpringBoot 3 (Java 17) 实现的平台"管理面"：用户登录/权限（Sa-Token）、项目/任务管理、
调用 Agent 服务并中继 SSE 事件（落库 `task_event` + 转发前端）、人审审批、产物下载、
模型 Key 加密管理。**不含任何 AI 逻辑**。

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/magent_biz?...`（自动建库） | 本地 MySQL |
| `DB_USER` / `DB_PASSWORD` | `root` / `root` | MySQL 账号 |
| `AGENT_BASE_URL` | `http://localhost:8001` | Agent 服务地址 |
| `INTERNAL_TOKEN` | `change-me` | 与 Agent 服务共享的内网密钥（两边一致，生产必改） |
| `AES_KEY` | `0123456789abcdef` | 模型 Key 加密密钥（16字节，生产必改） |
| `WORKSPACE_ROOT` | `../agent/workspace` | Agent 产物目录（供下载接口读取） |

## 启动

```powershell
mvn spring-boot:run          # 首次启动自动建表 + 种子数据
```

默认账号 **admin / admin123**（首次登录后请建新账号并修改）。

## API 一览（除登录外均需 Header `satoken: <token>`）

- `POST /api/auth/login` — 登录
- `GET/POST /api/users` — 用户管理（admin）
- `GET/POST /api/projects` — 项目
- `POST /api/tasks`、`GET /api/tasks?projectId=`、`GET /api/tasks/{id}` — 任务
- `GET /api/tasks/{id}/stream` — SSE 实时事件（断线后用 events 补拉）
- `GET /api/tasks/{id}/events?afterSeq=` — 事件补拉（按 seq 去重排序）
- `POST /api/tasks/{id}/approve` — 人审 `{decision: pass|reject, comment}`
- `POST /api/tasks/{id}/cancel` — 取消
- `GET /api/tasks/{id}/artifacts`、`GET /api/artifacts/{id}/download` — 产物
- `GET/POST /api/models` — 模型管理（Key AES 加密存储，返回脱敏，admin 可写）
- `GET /api/role-configs`、`PUT /api/role-configs/{role}` — 角色提示词/默认模型

## 任务状态机

`pending → running ⇄ waiting_review → done | failed | canceled`
（interrupt 事件 → waiting_review；approve → resume 回 running；终态幂等）

## 测试

```powershell
mvn test        # 31 个用例，H2(MySQL模式) + mock AgentClient，无需外部依赖
```
