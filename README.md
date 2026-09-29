# Bobo4J 后端

AgentDemo 是一个面向图片理解、图片生成与旅行助手场景的 Spring Boot 后端。它支持用户独立上传图片、等待后续指令、视觉识别、图片二次创作、图片生成，以及将图片发布到 Bobo's World。模型接入采用按用户配置的多供应商注册表，避免业务代码与某一家模型服务强绑定。

## 能力概览

- **多模型聊天与视觉识别**：GPT、Gemini、Qwen、GLM、HY 通过统一的 `ModelProvider`/模型配置接口接入。
- **图片生成 Worker**：Qwen 使用 DashScope 原生链路；GPT、GLM、HY 使用 OpenAI-compatible 图片编辑适配器。任务以异步队列方式执行，记录 provider、model、状态、重试次数和过期时间。
- **连续视觉对话**：可只发送一张图片，服务返回场景卡并挂起等待指令；后续可继续识别、生成或对原图进行二次创作，不要求预先绑定用户信息与图片。
- **视觉偏好记忆（RAG 基础能力）**：将场景卡、视觉偏好和来源写入 `vision_memory`，按用户和查询召回相关上下文；默认不会把记忆自动发送给第三方模型。
- **对象存储**：图片上传、签名 URL、缩略图和生成结果均通过 Aliyun OSS 管理。
- **Bobo's World**：支持图片、文案、匿名展示和可见性等发布信息，并校验资源所有权。
- **认证与用户模型配置**：登录会话、用户级模型/API Key 配置和能力目录由后端统一管理。

## 技术栈与模块

- Java 21、Spring Boot 4.1.1、Spring WebFlux
- Spring AI 2.0.1、MyBatis-Plus 3.5.16
- MySQL 8+、Redis（聊天记忆）、Aliyun OSS
- Maven 构建；默认端口 `18080`

主要包结构：

```text
com.bbb.exercise.agentdemo1_0
├─ auth          登录、会话与用户 API Key
├─ chat          对话编排、意图识别、视觉消息处理
├─ model         多模型目录、用户模型配置、Provider 注册表
├─ generation    图片生成 Provider、任务 Worker 与状态流转
├─ image         图片资源上传、签名 URL、图片任务 API
├─ memory        视觉偏好记忆与召回
├─ conversation  会话与消息持久化
├─ bobo          Bobo's World 发布与查询
├─ photo         图片归档
├─ zine          图文册/杂志生成
├─ oss           OSS 适配
└─ config        Web、序列化、任务等基础配置
```

## 模型供应商与能力

| Provider | 聊天 | 视觉 | 图片生成 | 说明 |
| --- | --- | --- | --- | --- |
| GPT | ✓ | ✓ | 兼容接口适配 | 需要用户配置对应模型和 Key |
| Gemini | ✓ | ✓ | — | 当前注册表未声明 IMAGE 能力 |
| Qwen | ✓ | ✓ | ✓ | 图片生成走 DashScope 原生链路 |
| GLM | ✓ | ✓ | 兼容接口适配 | 依赖供应商提供 `/images/edits` 兼容端点 |
| HY | ✓ | ✓ | 兼容接口适配 | 依赖供应商提供 OpenAI-compatible 端点 |

模型配置接口会返回当前能力目录，前端可据此让用户选择服务对象；真正调用时由 `ModelProviderRegistry` 和 `ImageGenerationProviderRegistry` 解析对应 provider/model，而不是在 Controller 中写死供应商。

## 典型请求流程

### 图片理解与二次创作

```text
上传图片/发送图片消息
        ↓
ChatService 解析消息与用户选择的模型
        ↓
VISION 调用 → 生成 SCENE_CARD → WAITING_FOR_INSTRUCTION
        ↓                         ↑
识别/生成/二次创作指令 ───────────┘
        ↓
ImageJobService 入队 → ImageJobWorker → OSS 输出 → 返回任务状态
```

### 图片生成任务

```text
POST /api/chat 或 /api/image-jobs
        ↓
写入 image_job(status=QUEUED, provider, model)
        ↓
Worker 轮询并选择适配器
        ↓
DashScope 或 OpenAI-compatible Provider
        ↓
保存 output_object_key，状态变为 SUCCEEDED/FAILED
```

## 主要 API

具体字段以 Controller 的 DTO 为准，以下是常用入口：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/auth/register`、`/api/auth/login` | 注册与登录 |
| `GET` | `/api/auth/me` | 获取当前会话用户 |
| `GET` | `/api/settings/models` | 查询模型与能力目录 |
| `POST` | `/api/settings/models` | 保存用户模型配置 |
| `DELETE` | `/api/settings/models/{id}` | 删除模型配置 |
| `POST` | `/api/chat` | 发送文本、图片或继续指令 |
| `GET` | `/api/conversations` | 查询会话 |
| `POST` | `/api/images/upload` | 创建图片上传策略 |
| `GET` | `/api/image-jobs/{id}` | 查询异步图片任务 |
| `GET` | `/api/settings/visual-memory/context` | 召回用户视觉偏好上下文 |
| `GET/POST` | `/api/bobo-world/**` | Bobo's World 图片发布与管理 |
| `GET` | `/api/health` | 健康检查 |

图片生成任务至少应保存来源资源、`provider`、`model` 和生成模式；不要只依赖默认 Qwen 值，否则旧数据无法准确追踪供应商。

## 本地环境

### 前置依赖

- JDK 21
- Maven 3.9+
- MySQL 8+
- Redis 6+（用于 Spring AI 聊天记忆；仅运行不依赖聊天记忆的接口时可暂不启用）
- 可访问的 Aliyun OSS；按需准备 GPT、Gemini、Qwen、GLM、HY 的用户级模型配置

### 数据库初始化与迁移

1. 创建数据库（默认名 `bobo_db`）和具备建表权限的用户。
2. 执行 [`src/main/resources/schema.sql`](src/main/resources/schema.sql)。应用启动时不会自动执行建表脚本。
3. 从旧版本升级时，仅执行一次 [`src/main/resources/db/migrations/20260928_image_job_provider_model.sql`](src/main/resources/db/migrations/20260928_image_job_provider_model.sql)，为 `image_job` 增加 `provider` 和 `model` 字段。

迁移脚本针对旧版 MySQL 保持简单 `ALTER TABLE` 语句。执行前请查询 `information_schema.columns`，确认字段不存在；重复执行会报“Duplicate column”，不应在生产环境盲目重跑。

### 配置

默认配置在 `src/main/resources/application.yml`，敏感值通过环境变量注入。常用变量如下（示例值均为占位符）：

```powershell
$env:MYSQL_HOST = "127.0.0.1"
$env:MYSQL_PORT = "3306"
$env:MYSQL_DATABASE = "bobo_db"
$env:MYSQL_USERNAME = "bobo_user"
$env:MYSQL_PASSWORD = "<your-password>"
$env:REDIS_HOST = "127.0.0.1"
$env:ALIYUN_OSS_REGION = "cn-beijing"
$env:ALIYUN_OSS_BUCKET = "<your-bucket>"
$env:ALIYUN_OSS_ACCESS_KEY_ID = "<your-access-key-id>"
$env:ALIYUN_OSS_ACCESS_KEY_SECRET = "<your-access-key-secret>"
```

用户模型 Key 通过 `/api/settings/models` 保存到用户配置表。不要把真实 Key 写入 `application.yml`、日志、README 或 Git。

### 启动与构建

```powershell
# 开发启动
mvn spring-boot:run

# 打包
mvn -DskipTests package

# 运行打包产物
java -jar target/agentDemo1_0-0.0.1-SNAPSHOT.jar
```

服务默认监听 `127.0.0.1:18080`；反向代理部署时，建议只对外暴露 Nginx/网关，并将 `/api` 转发到该端口。

## 测试与排障

```powershell
# Provider 注册表和图片适配器回归测试
mvn -q -Dtest=ModelProviderRegistryTest,ImageGenerationProviderRegistryTest test

# 完整测试与打包
mvn test
mvn -DskipTests package
```

若完整测试在 Spring AI Redis Chat Memory 初始化阶段超时，通常是本机 Redis/Redis Stack 未启动或端口不一致；先检查 `REDIS_HOST`、`REDIS_PORT` 和 Redis 可用性，再重试。该问题不会影响不使用会话记忆的纯 HTTP 编译检查。

遇到 `image_job` 插入字段数量或 SQL 语法错误时，优先检查数据库是否执行过上述迁移，以及 `DESCRIBE image_job` 是否包含 `provider`、`model`。遇到模型调用失败时，检查用户配置的 provider、model、Key 和该 provider 是否声明了目标能力。

## 部署建议

- 使用 systemd、Docker Compose 或同类进程管理器运行 jar，并设置 graceful shutdown。
- 生产环境将数据库、Redis、OSS 和模型 Key 放到 Secret/环境变量，不提交 `.env`。
- Nginx/网关负责 TLS、上传大小限制和 `/api` 路由；应用本身继续监听内网端口。
- 监控 `/api/health`、队列中 `QUEUED/RUNNING` 任务数量、失败率和 OSS 签名 URL 错误。
- 发布新版本前先备份数据库，再执行对应迁移；至少保留上一版本 jar 以便回滚。

## 后续扩展边界

- 为图片生成 Provider 增加统一的超时、限流、熔断和费用统计接口。
- 将 `vision_memory.embedding_json` 接入真正的向量数据库/向量索引，以提升长期偏好召回质量。
- 为每个模型配置增加可用性探测、能力校验和配额提示。
- 将异步 Worker 的状态事件接入消息队列或 SSE，减少前端轮询。

