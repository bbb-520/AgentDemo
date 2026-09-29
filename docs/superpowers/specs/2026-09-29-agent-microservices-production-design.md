# AgentDemo 多模块微服务生产基线设计

## 目标

在不改变现有 Spring Boot 4.1.1 和 Spring AI 2.0.1 基线的前提下，将 AgentDemo 从单体应用演进为可部署的多模块微服务架构，支持 Nacos、Spring Cloud Gateway、OpenFeign、Sentinel，并为后续多 Agent 协作和 AgentRevise 工作流预留稳定边界。

## 现状与约束

- 当前应用是 Spring Boot 4.1.1、Java 21、WebFlux、Spring AI 2.0.1。
- 当前业务包括认证、用户模型配置、SSE 对话、视觉记忆、图片上传、图片生成任务、OSS、Bobo World、Photo 和 Zine。
- 不升级或降级 Spring Boot。
- 保留现有 HTTP API 语义，优先通过网关兼容原有 `/api/**` 路径。
- 不覆盖工作区中已有的 `ImageJobController.java` 和 `ModelProfileService.java` 修改。
- 生产部署需要包含配置隔离、健康检查、优雅停机、日志、数据库迁移、容器编排和回滚说明。

## 架构决策

### 模块

```text
agent-demo-parent
├── agent-common
├── agent-api
├── agent-model-core
├── agent-gateway
├── agent-auth-service
├── agent-chat-service
├── agent-media-service
├── agent-content-service
└── agent-orchestrator-service
```

`agent-model-core` 是共享库，不在第一阶段单独部署为模型微服务。它负责模型能力描述、Provider 适配、路由、超时、降级和调用记录抽象。用户 API Key 和用户模型配置仍由 `agent-auth-service` 持有。

`agent-orchestrator-service` 负责 Agent 定义、任务运行、工作流、Agent 间消息、Reviewer/Revise 节点和未来的多 Agent 协作。AgentRevise 作为一个可配置 Agent/Workflow Node 实现，不单独拆服务。

### 服务边界

- `agent-gateway`：路由、请求 ID、CORS、鉴权透传、上传限制和 Sentinel 网关限流。
- `agent-auth-service`：用户、登录会话、用户 API Key、用户模型配置。
- `agent-chat-service`：会话、消息、视觉记忆、SSE 对话入口。
- `agent-media-service`：图片资产、图片生成任务、OSS 和 Worker。
- `agent-content-service`：Bobo World、Photo、Zine。
- `agent-orchestrator-service`：多 Agent 任务、工作流、审阅与修订。

每个服务拥有自己的表和迁移脚本；服务之间不做跨库 JOIN 或跨服务外键，只通过 ID 和内部 HTTP API 通信。

### 服务间通信

- OpenFeign 用于短耗时、非流式的内部查询和命令，例如用户身份、资源归属和任务状态。
- SSE、长耗时模型调用、大文件上传不通过 Feign；继续使用 WebClient 或服务内部直接调用。
- Feign 调用配置超时、重试上限、Sentinel fallback 和统一错误码。

### 基础设施

- Nacos：服务注册、服务发现和配置中心；配置按 namespace、group、dataId 隔离。
- Gateway：基于 WebFlux，按服务名路由到 Nacos 实例。
- Sentinel：网关路由限流、服务资源限流、Feign 调用降级和图片生成保护。
- MySQL：服务逻辑库隔离；迁移由 Flyway 管理。
- Redis：chat-service 的 Chat Memory、短期任务状态和幂等辅助。
- OSS：media-service 统一管理图片对象。

### 数据归属

| 服务 | 主要表 |
| --- | --- |
| auth | `app_user`、`auth_session`、`user_api_key`、`user_model_profile` |
| chat | `chat_conversation`、`chat_message`、`vision_memory` |
| media | `image_asset`、`image_job` |
| content | `bobo_world_item` |
| orchestrator | agent definition、task、run、step、message、revision 相关表 |

第一阶段可以继续使用同一个 MySQL 实例，但必须使用逻辑库或 Schema 隔离，禁止新增跨服务外键。

### 生产基线

- 保留 Spring Boot 4.1.1、Java 21 和 Spring AI 2.0.1。
- Spring Cloud、Spring Cloud Alibaba、Gateway、Feign 和 Sentinel 版本必须通过 BOM 统一管理；对 Boot 4.1.1 的组合先执行依赖树和启动冒烟验证，不在未验证前承诺组件版本兼容。
- 所有敏感配置通过环境变量或 Nacos 加密配置注入，不提交真实 API Key、数据库密码或 OSS 密钥。
- 暴露 `/actuator/health/readiness` 和 `/actuator/health/liveness`，并配置优雅停机。
- API、Worker、Gateway 可独立构建和部署；镜像使用非 root 用户和固定版本标签。

## 部署拓扑

```text
Internet
   |
Reverse Proxy / Load Balancer
   |
agent-gateway (2+ replicas)
   |
Nacos discovery
   |--------- auth-service
   |--------- chat-service (2+ replicas)
   |--------- media-service-api
   |--------- media-service-worker (独立扩容)
   |--------- content-service
   |--------- orchestrator-service

MySQL / Redis / OSS / Sentinel Dashboard
```

开发和验收阶段使用 Docker Compose；生产环境优先使用 Kubernetes 或 systemd + Docker。部署文档需要涵盖镜像构建、Nacos 配置导入、数据库迁移、启动顺序、健康检查、日志和回滚。

## 迁移策略

1. 建立父 POM、公共模块、Nacos/Gateway/Sentinel 基础依赖和 Compose 基础设施。
2. 先抽离 media-service，验证图片任务和 Worker 独立部署。
3. 抽离 auth-service，接入网关鉴权和 Feign 用户查询。
4. 抽离 chat-service，保留 SSE，接入 Redis Chat Memory。
5. 抽离 content-service。
6. 建立 orchestrator-service，实现最小多 Agent 工作流和 AgentRevise。
7. 增加生产观测、限流规则、灰度和回滚验证。

## 非目标

- 第一阶段不引入 Seata、RocketMQ 或 Kubernetes Operator。
- 第一阶段不把模型调用强制抽成独立 `agent-model-service`。
- 第一阶段不重写现有前端和业务 API。

