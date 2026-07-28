# SpringBoot 业务后端（server/）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现平台"管理面"：用户登录/权限、项目/任务管理、调用 Agent 服务并转发+落库 SSE 事件、人审审批、产物与模型管理。

**Architecture:** 单体 SpringBoot 3 (Java 17, Maven)。MyBatis-Plus 访问本地 MySQL（`magent_biz`）；Sa-Token 鉴权（MVP 内存会话，Redis 留待后续）；对 Agent 服务的调用封装在 `AgentClient` 接口后（测试用 mock）；SSE 中继用 JDK HttpClient 后台线程消费 Agent 事件流 → 落库 `task_event` → 经 `SseEmitter` 转发前端。

**Tech Stack:** SpringBoot 3.2.x, MyBatis-Plus 3.5.x, Sa-Token 1.39(spring-boot3-starter), MySQL 8 / H2(测试, MySQL 模式), spring-security-crypto(BCrypt/AES), JUnit5 + Mockito

**对应设计文档：** 第 2.2、4.1(消费侧)、4.2(前端API)、4.3、4.4、5 节

**MVP 偏差（有意为之）：**
- 不引入 Redis：Sa-Token 用内存会话、SSE 会话注册表用内存 Map（单实例内部工具够用）
- Token 用量上限对账（设计5节）延后：本计划只落 `task_event`，token 统计需网关配合，归入计划4
- `GET /api/users` 管理接口仅 admin 可用的最小实现（列表+创建）

---

## 文件结构

```
server/
  pom.xml
  src/main/java/com/magent/server/
    ServerApplication.java
    config/ (SaTokenConfig, GlobalExceptionHandler, AppProps)
    common/ (Result.java, BizException.java, AesUtil.java)
    entity/ (SysUser, Project, Task, TaskEvent, Artifact, LlmModel, AgentRoleConfig)
    mapper/ (7 个 BaseMapper 接口)
    service/ (AuthService, TaskService, TaskEventService, AgentClient[接口],
              HttpAgentClient, AgentStreamRelay, SseRegistry)
    controller/ (AuthController, UserController, ProjectController,
                 TaskController, ArtifactController, ModelController, RoleConfigController)
  src/main/resources/ (application.yml, schema.sql, data.sql)
  src/test/java/com/magent/server/ (对应测试)
  src/test/resources/application-test.yml
```

## 状态机（task.status）

`pending → running → waiting_review → running → … → done | failed | canceled`
- 收到 `interrupt` 事件 → `waiting_review`；approve(pass/reject) → 调 Agent resume → `running`
- 收到 `task_done` → `done`；`task_failed` → `failed`；用户取消 → `canceled`
- 非法迁移抛 `BizException(409)`

---

### Task 1: Maven 骨架 + schema + 上下文启动测试

**Files:** `server/pom.xml`, `ServerApplication.java`, `application.yml`, `application-test.yml`, `schema.sql`, `data.sql`, `ContextLoadsTest.java`

- [ ] Step 1: pom.xml — parent `spring-boot-starter-parent:3.2.7`；依赖：web, validation, mybatis-plus-spring-boot3-starter:3.5.7, mysql-connector-j, sa-token-spring-boot3-starter:1.39.0, spring-security-crypto, lombok, h2(test), spring-boot-starter-test(test)
- [ ] Step 2: `application.yml`：

```yaml
server: { port: 8080 }
spring:
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/magent_biz?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:root}
  sql.init: { mode: always, continue-on-error: false }
app:
  agent-base-url: ${AGENT_BASE_URL:http://localhost:8001}
  internal-token: ${INTERNAL_TOKEN:change-me}
  aes-key: ${AES_KEY:0123456789abcdef}   # 16字节，生产必改
sa-token: { token-name: satoken, timeout: 86400 }
```

`application-test.yml`：H2 `jdbc:h2:mem:test;MODE=MySQL;DATABASE_TO_LOWER=TRUE`

- [ ] Step 3: `schema.sql`（全部 `CREATE TABLE IF NOT EXISTS`，7 张表，与设计 4.3 一致）：

```sql
CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password VARCHAR(100) NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'member',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS project (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  owner_id BIGINT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id BIGINT NOT NULL,
  requirement TEXT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'pending',
  auto_mode TINYINT NOT NULL DEFAULT 0,
  current_node VARCHAR(32),
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS task_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  seq INT NOT NULL,
  event VARCHAR(32) NOT NULL,
  agent VARCHAR(32),
  data TEXT,
  ts BIGINT,
  UNIQUE KEY uk_task_seq (task_id, seq));
CREATE TABLE IF NOT EXISTS artifact (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  name VARCHAR(255) NOT NULL,
  type VARCHAR(32) NOT NULL,
  path VARCHAR(500) NOT NULL,
  version INT NOT NULL DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS llm_model (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  litellm_model_name VARCHAR(128) NOT NULL,
  api_key_enc VARCHAR(500),
  enabled TINYINT NOT NULL DEFAULT 1);
CREATE TABLE IF NOT EXISTS agent_role_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role VARCHAR(32) NOT NULL UNIQUE,
  system_prompt TEXT,
  default_model_id BIGINT);
```

`data.sql`：幂等插入 admin（BCrypt of `admin123`）与 5 条角色配置（`INSERT ... SELECT ... WHERE NOT EXISTS`，H2/MySQL 兼容写法用 MERGE 不行，统一用 `INSERT INTO ... SELECT` 防重）。
- [ ] Step 4: `ContextLoadsTest`（`@SpringBootTest @ActiveProfiles("test")`）红→绿
- [ ] Step 5: `mvn -q test` 通过后 commit `feat(server): Maven骨架/建表脚本/上下文测试`

### Task 2: 公共层 + 实体/Mapper

**Files:** common/(Result, BizException, AesUtil), entity/7个, mapper/7个, config/GlobalExceptionHandler, 测试 MapperCrudTest / AesUtilTest

- [ ] Step 1: 失败测试：AesUtilTest（加密→解密还原；`mask("sk-abcdef1234")=="sk-***1234"`）；MapperCrudTest（`@MybatisPlusTest` 或 `@SpringBootTest`：插入 SysUser/Task 后能查回，task 默认 status=pending）
- [ ] Step 2: 实现：
  - `Result<T>{int code; String message; T data;}` + `ok()/fail()` 静态方法
  - `BizException(int httpStatus, String message)`；`GlobalExceptionHandler`：BizException→对应状态码 `{code,message,detail}`；`NotLoginException`→401；`NotRoleException`→403；其余→500
  - `AesUtil`：AES/GCM，key 来自 `app.aes-key`；`encrypt/decrypt/mask`
  - 实体：Lombok `@Data` + `@TableName`；字段与 schema 一致（驼峰映射）
- [ ] Step 3: 绿灯 commit `feat(server): 公共层/实体/Mapper`

### Task 3: 认证与用户（Sa-Token）

**Files:** config/SaTokenConfig, service/AuthService, controller/(AuthController, UserController), 测试 AuthApiTest

- [ ] Step 1: 失败测试（MockMvc）：
  - `POST /api/auth/login {admin/admin123}` → 200 且 data.token 非空、data.role=admin
  - 错密码 → 401；未登录访问 `GET /api/projects` → 401
  - admin 带 token `POST /api/users {bob/pwd123/member}` → 200；member token 调 `POST /api/users` → 403
- [ ] Step 2: 实现：
  - `SaTokenConfig`：`SaInterceptor` 拦截 `/api/**`，放行 `/api/auth/login`；登录后 `StpUtil.getSession().set("role", ...)`
  - `AuthService.login`：查用户→BCrypt 校验→`StpUtil.login(id)`→返回 `{token, username, role}`
  - `UserController`：`@SaCheckRole("admin")` 建用户（BCrypt 存）、列表（不回传密码）
- [ ] Step 3: 绿灯 commit `feat(server): Sa-Token认证与用户管理`

### Task 4: 项目 CRUD + 任务创建/状态机

**Files:** controller/(ProjectController, TaskController 部分), service/TaskService, service/AgentClient(接口), 测试 ProjectApiTest / TaskServiceTest

- [ ] Step 1: 失败测试：
  - 项目：创建→列表可见（owner=当前用户）
  - `TaskService.create`：落库 pending → 调 `AgentClient.startTask(taskId,...)`（Mockito mock 验证参数 `T{id}`、role_models 来自 agent_role_config+llm_model 联查）→ 状态 running
  - AgentClient 抛异常 → task 状态 failed，BizException 上抛
  - 状态机：`markWaitingReview` 仅当 running；`markDone/markFailed` 幂等；非法迁移 409
- [ ] Step 2: 实现：
  - `AgentClient` 接口：`void startTask(String taskId, String requirement, boolean autoMode, Map<String,String> roleModels, Map<String,String> rolePrompts); void resume(String taskId, String decision, String comment); void cancel(String taskId);`
  - `TaskService`：create/transition 方法集中管理状态迁移表
  - `TaskController`：`POST /api/tasks`、`GET /api/tasks?projectId=`、`GET /api/tasks/{id}`
- [ ] Step 3: 绿灯 commit `feat(server): 项目与任务创建/状态机`

### Task 5: HttpAgentClient + SSE 中继落库

**Files:** service/(HttpAgentClient, AgentStreamRelay, SseRegistry, TaskEventService), 测试 AgentStreamRelayTest / TaskEventServiceTest

- [ ] Step 1: 失败测试：
  - `TaskEventService.save`：入库；同 (task_id,seq) 重复保存忽略（幂等）；`listAfter(taskId, afterSeq)` 有序返回
  - `AgentStreamRelay.handleLine("data:{...}")`：解析事件 → 落库 → 按类型驱动状态机（interrupt→waiting_review、task_done→done、task_failed→failed、artifact_created→artifact 表登记 name/type/path）→ 转发 SseRegistry（用 mock 验证）
  - 非 data: 行与坏 JSON：跳过不抛
- [ ] Step 2: 实现：
  - `HttpAgentClient`：JDK HttpClient；POST JSON 带 `X-Internal-Token`；非 2xx 抛 BizException(502)
  - `AgentStreamRelay`：`startRelay(taskId)` 起守护线程 GET `/agent/tasks/T{id}/stream`，逐行 `handleLine`；流结束/异常时若任务仍 running → 标 failed(`stream lost`)；`TaskService.create` 成功后调用 startRelay
  - `SseRegistry`：`ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>`，`add/remove/broadcast`（发送失败即移除）
- [ ] Step 3: 绿灯 commit `feat(server): Agent客户端与SSE中继落库`

### Task 6: 前端 SSE / 事件补拉 / 审批 / 取消

**Files:** TaskController 补全, 测试 TaskFlowApiTest

- [ ] Step 1: 失败测试（MockMvc + mock AgentClient）：
  - `GET /api/tasks/{id}/events?afterSeq=0` 返回落库事件
  - `GET /api/tasks/{id}/stream` 返回 `text/event-stream`
  - 任务置 waiting_review 后 `POST /api/tasks/{id}/approve {decision:pass}` → 调 `AgentClient.resume` → running；非 waiting_review 审批 → 409
  - `POST /api/tasks/{id}/cancel` → AgentClient.cancel + status canceled
- [ ] Step 2: 实现：stream 端点注册 SseEmitter(超时 30min，onCompletion 移除)；approve/cancel 走 TaskService
- [ ] Step 3: 绿灯 commit `feat(server): 任务SSE/补拉/审批/取消接口`

### Task 7: 产物 / 模型 / 角色配置接口

**Files:** controller/(ArtifactController, ModelController, RoleConfigController), 测试 AdminApiTest

- [ ] Step 1: 失败测试：
  - `GET /api/tasks/{id}/artifacts` 列表；`GET /api/artifacts/{id}/download` 返回文件字节（路径 = `${WORKSPACE_ROOT:../agent/workspace}/T{taskId}/{path}`，须做 commonpath 防逃逸校验）
  - `POST /api/models {name, litellmModelName, apiKey}` → api_key_enc 加密落库；`GET /api/models` 返回 `apiKeyMasked`，绝不回明文；仅 admin 可写
  - `PUT /api/role-configs/{role} {systemPrompt, defaultModelId}` 更新
- [ ] Step 2: 实现（download 用 `FileSystemResource`，Content-Disposition attachment）
- [ ] Step 3: 绿灯 commit `feat(server): 产物/模型/角色配置接口`

### Task 8: README + 全量回归

- [ ] `server/README.md`：环境变量表（DB_URL/DB_USER/DB_PASSWORD/AGENT_BASE_URL/INTERNAL_TOKEN/AES_KEY）、启动 `mvn spring-boot:run`、默认账号 admin/admin123（提示改密）、测试 `mvn test`
- [ ] `mvn -q test` 全绿 → commit `docs(server): README与全量回归`

## 自审记录

1. Spec 覆盖：4.2 前端 API 全部端点 → Task 3/4/6/7；4.3 七表 → Task 1；4.4 数据流 → Task 4/5/6；5 节（对账定时器简化为流断线标 failed + 重试靠断点 resume，Key 加密脱敏 → Task 7；seq 幂等 → Task 5）；2.2 职责边界（无 AI 逻辑）成立。
2. 无占位符；接口签名跨任务一致（AgentClient 三方法在 Task 4 定义、5 实现、6 消费）。
3. 类型一致：taskId 对 Agent 侧统一 `"T"+task.id` 字符串，DB 内为 bigint。
