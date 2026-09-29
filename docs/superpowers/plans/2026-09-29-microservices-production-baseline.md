# AgentDemo 微服务生产基线实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 Spring Boot 4.1.1、Java 21 和 Spring AI 2.0.1 的前提下，将当前单体改造成可本地运行、可独立部署的多模块微服务基线，并接入 Nacos、Gateway、OpenFeign、Sentinel。

**Architecture:** 先建立 Maven 多模块父工程和公共契约，再将当前业务作为可运行服务逐步迁移。第一阶段采用“API Gateway + auth/chat/media/content/orchestrator 服务 + agent-model-core 公共库”，保留现有业务 API，通过 Nacos 服务发现和配置中心连接服务，使用 Docker Compose 验证本地部署。

**Tech Stack:** Spring Boot 4.1.1, Java 21, Spring AI 2.0.1, Spring Cloud Gateway WebFlux, Spring Cloud OpenFeign, Spring Cloud Alibaba Nacos/Sentinel, MySQL, Redis, Docker Compose, Flyway, Actuator.

**Spec:** `docs/superpowers/specs/2026-09-29-agent-microservices-production-design.md`

## Global Constraints

- 保留 Spring Boot 4.1.1、Java 21 和 Spring AI 2.0.1。
- 不覆盖现有 `ImageJobController.java` 和 `ModelProfileService.java` 修改。
- 第一阶段不把模型调用拆成独立 `agent-model-service`；`agent-model-core` 作为共享库。
- Gateway 保留现有 `/api/**` 路径语义。
- 服务之间不跨库 JOIN、不新增跨服务外键。
- 本地先使用 Docker Compose；未经用户审核不提交或推送 GitHub。

## Review Focus

- Boot 4.1.1 与 Spring Cloud Alibaba/Sentinel 依赖冲突：Task 1 增加依赖树和启动冒烟验证。
- WebFlux SSE 被 Feign 阻塞：Task 4 用流式接口契约测试确保聊天响应仍为 `text/event-stream`。
- Nacos 不可用时服务启动行为：Task 2 增加 optional/import-check 配置与健康检查测试。
- Sentinel 限流和 fallback 导致错误格式不一致：Task 3/5 固定统一错误码响应。
- Worker 重复消费与服务滚动发布：Task 6 增加任务租约/幂等测试和独立 Worker 配置。

---

### Task 1: Maven 父工程与公共基础模块

**Files:**
- Modify: `pom.xml`（改为 `pom` packaging，增加模块和 Spring Cloud/SCA BOM 管理，保留 Boot 4.1.1 与 Spring AI 2.0.1）
- Create: `agent-common/pom.xml`
- Create: `agent-common/src/main/java/com/bbb/exercise/agentdemo/common/api/ApiError.java`
- Create: `agent-common/src/main/java/com/bbb/exercise/agentdemo/common/api/ApiResponse.java`
- Create: `agent-common/src/main/java/com/bbb/exercise/agentdemo/common/trace/RequestId.java`
- Create: `agent-api/pom.xml`
- Create: `agent-api/src/main/java/com/bbb/exercise/agentdemo/api/AuthInternalApi.java`
- Create: `agent-api/src/main/java/com/bbb/exercise/agentdemo/api/MediaInternalApi.java`
- Create: `agent-api/src/main/java/com/bbb/exercise/agentdemo/api/dto/UserIdentityDto.java`
- Test: `agent-common/src/test/java/com/bbb/exercise/agentdemo/common/api/ApiResponseTest.java`

**Interfaces:**
- `ApiResponse<T>` 提供 `success(T)` 和 `failure(String code, String message)`。
- API 模块只包含 DTO 和 Feign 接口，不依赖数据库或业务实现。

- [ ] **Step 1: Write the failing test** — 验证 `ApiResponse.success` 和 `failure` 的 JSON 字段。
- [ ] **Step 2: Run test to verify it fails** — `mvn -pl agent-common -am test -Dtest=ApiResponseTest`，预期模块尚不存在或测试失败。
- [ ] **Step 3: Implement parent/common/api modules** — 保持现有依赖，增加统一 BOM 和模块目录。
- [ ] **Step 4: Run test to verify it passes** — `mvn -pl agent-common -am test`，预期 PASS。
- [ ] **Step 5: Commit** — `git add pom.xml agent-common agent-api && git commit -m "build: add multi-module parent and shared contracts"`。

### Task 2: Nacos 配置、服务发现与生产配置模板

**Files:**
- Create: `infra/docker-compose.yml`
- Create: `infra/nacos/import/*.yml`
- Create: `agent-common/src/main/resources/application-common.yml`
- Create: `agent-gateway/src/main/resources/application.yml`
- Modify: `src/main/resources/application.yml`（迁移为本地兼容配置模板，不写入密钥）
- Create: `docs/deployment/local-compose.md`
- Test: `agent-common/src/test/java/com/bbb/exercise/agentdemo/common/config/ConfigurationContractTest.java`

**Interfaces:**
- 所有服务使用形如 `spring.config.import=optional:nacos:agent-gateway.yml?group=AGENT_GROUP&refreshEnabled=true` 的导入配置。
- Nacos dataId 命名为 `<service-name>.yml`，group 为 `AGENT_GROUP`。
- Compose 暴露 Nacos 8848、Sentinel Dashboard 8080、MySQL 3306、Redis 6379。

- [ ] **Step 1: Write the failing configuration test** — 检查 service name、Nacos server address、Actuator readiness 配置存在。
- [ ] **Step 2: Run test to verify it fails** — `mvn -pl agent-common -am test -Dtest=ConfigurationContractTest`。
- [ ] **Step 3: Add Compose and Nacos templates** — 配置 namespace/group/dataId、健康检查、优雅停机和敏感变量占位符。
- [ ] **Step 4: Run local infrastructure smoke test** — `docker compose -f infra/docker-compose.yml config`，预期配置合法。
- [ ] **Step 5: Commit** — `git add infra docs/deployment agent-common/src/main/resources agent-gateway/src/main/resources src/main/resources/application.yml && git commit -m "ops: add local nacos sentinel mysql redis baseline"`。

### Task 3: Gateway 服务与 Sentinel 网关保护

**Files:**
- Create: `agent-gateway/pom.xml`
- Create: `agent-gateway/src/main/java/com/bbb/exercise/agentdemo/gateway/GatewayApplication.java`
- Create: `agent-gateway/src/main/java/com/bbb/exercise/agentdemo/gateway/config/GatewayRoutes.java`
- Create: `agent-gateway/src/main/java/com/bbb/exercise/agentdemo/gateway/filter/RequestIdGlobalFilter.java`
- Create: `agent-gateway/src/main/java/com/bbb/exercise/agentdemo/gateway/filter/AuthRelayGlobalFilter.java`
- Create: `agent-gateway/src/main/resources/application.yml`
- Test: `agent-gateway/src/test/java/com/bbb/exercise/agentdemo/gateway/GatewayRouteTest.java`

**Interfaces:**
- `/api/auth/**` -> `lb://agent-auth-service`
- `/api/chat/**` -> `lb://agent-chat-service`
- `/api/images/**` and `/api/image-jobs/**` -> `lb://agent-media-service`
- `/api/bobo-world/**` and `/api/zines/**` -> `lb://agent-content-service`

- [ ] **Step 1: Write route tests** — 验证路径、服务名和 `X-Request-Id` 透传。
- [ ] **Step 2: Run tests to verify failure** — `mvn -pl agent-gateway -am test -Dtest=GatewayRouteTest`。
- [ ] **Step 3: Implement Gateway application and Sentinel gateway adapter** — 不引入 MVC starter，保持 WebFlux。
- [ ] **Step 4: Run tests and start gateway** — `mvn -pl agent-gateway -am test`；Compose 启动后访问 `/actuator/health`。
- [ ] **Step 5: Commit** — `git add agent-gateway && git commit -m "feat: add gateway routing and sentinel protection"`。

### Task 4: 抽取 agent-model-core 与 Agent Orchestrator 骨架

**Files:**
- Create: `agent-model-core/pom.xml`
- Create: `agent-model-core/src/main/java/com/bbb/exercise/agentdemo/model/ModelDescriptor.java`
- Create: `agent-model-core/src/main/java/com/bbb/exercise/agentdemo/model/ModelRouter.java`
- Create: `agent-model-core/src/main/java/com/bbb/exercise/agentdemo/model/ModelInvocation.java`
- Create: `agent-orchestrator-service/pom.xml`
- Create: `agent-orchestrator-service/src/main/java/com/bbb/exercise/agentdemo/orchestrator/OrchestratorApplication.java`
- Create: `agent-orchestrator-service/src/main/java/com/bbb/exercise/agentdemo/orchestrator/domain/AgentDefinition.java`
- Create: `agent-orchestrator-service/src/main/java/com/bbb/exercise/agentdemo/orchestrator/domain/AgentRun.java`
- Create: `agent-orchestrator-service/src/main/java/com/bbb/exercise/agentdemo/orchestrator/service/AgentWorkflowService.java`
- Create: `agent-orchestrator-service/src/main/java/com/bbb/exercise/agentdemo/orchestrator/web/AgentWorkflowController.java`
- Test: `agent-orchestrator-service/src/test/java/com/bbb/exercise/agentdemo/orchestrator/AgentWorkflowServiceTest.java`

**Interfaces:**
- `ModelRouter.route(ModelInvocation)` 返回选定 Provider 和模型。
- `AgentWorkflowService.start(String workflow, String input, String userId)` 返回 `AgentRun`。
- 初始 workflow 支持 `REVISE`: `DRAFT -> REVIEW -> REVISE -> VALIDATE`。

- [ ] **Step 1: Write workflow tests** — 验证 revise 工作流状态转移和非法状态拒绝。
- [ ] **Step 2: Run tests to verify failure** — `mvn -pl agent-orchestrator-service -am test -Dtest=AgentWorkflowServiceTest`。
- [ ] **Step 3: Implement model-core and orchestrator skeleton** — 先使用内存状态，接口保持可替换为持久化实现。
- [ ] **Step 4: Run tests and actuator smoke test** — `mvn -pl agent-orchestrator-service -am test`。
- [ ] **Step 5: Commit** — `git add agent-model-core agent-orchestrator-service pom.xml && git commit -m "feat: add model core and revise workflow skeleton"`。

### Task 5: 服务模块化与内部 Feign 契约

**Files:**
- Create: `agent-auth-service/pom.xml` and service bootstrap/resources.
- Create: `agent-chat-service/pom.xml` and service bootstrap/resources.
- Create: `agent-media-service/pom.xml` and service bootstrap/resources.
- Create: `agent-content-service/pom.xml` and service bootstrap/resources.
- Modify: `agent-api/src/main/java/com/bbb/exercise/agentdemo/api/AuthInternalApi.java`
- Modify: `agent-api/src/main/java/com/bbb/exercise/agentdemo/api/MediaInternalApi.java`
- Create: service-specific `@FeignClient` adapters and fallback classes.
- Test: one contract test per service verifying application name and internal endpoint mapping.

**Interfaces:**
- auth service exposes `/internal/auth/users/{userId}`。
- media service exposes `/internal/media/assets/{assetId}/ownership`。
- Feign fallback returns typed `ApiResponse.failure`，不暴露堆栈和密钥。

- [ ] **Step 1: Write service contract tests** — 固定内部路径、服务名和 fallback 错误码。
- [ ] **Step 2: Run tests to verify failure** — `mvn -pl agent-auth-service,agent-chat-service,agent-media-service,agent-content-service -am test`。
- [ ] **Step 3: Create service bootstraps and migrate business packages incrementally** — 优先复制最小可运行入口，再迁移 Controller/Service/Mapper。
- [ ] **Step 4: Run per-service tests** — 依次运行 `mvn -pl agent-auth-service -am test`、`mvn -pl agent-chat-service -am test`、`mvn -pl agent-media-service -am test` 和 `mvn -pl agent-content-service -am test`。
- [ ] **Step 5: Commit** — `git add agent-*-service agent-api && git commit -m "feat: add domain service modules and feign contracts"`。

### Task 6: 数据库迁移、Worker 独立部署与幂等

**Files:**
- Create: `infra/mysql/migrations/V1__baseline.sql`
- Create: `infra/mysql/migrations/V2__orchestrator.sql`
- Modify: `agent-media-service/src/main/java/com/bbb/exercise/agentdemo/media/worker/ImageJobWorker.java`
- Create: `agent-media-service/src/main/java/com/bbb/exercise/agentdemo/media/worker/WorkerLeaseService.java`
- Create: `agent-media-service/src/main/resources/application-worker.yml`
- Test: `agent-media-service/src/test/java/com/bbb/exercise/agentdemo/media/worker/WorkerLeaseServiceTest.java`

**Interfaces:**
- `WorkerLeaseService.tryAcquire(jobId, workerId, Duration)` 返回 boolean。
- Worker 只有成功取得租约才执行模型调用。
- 任务状态迁移必须幂等，重复完成不能覆盖已成功结果。

- [ ] **Step 1: Write lease/idempotency tests** — 验证并发 Worker 只有一个获胜、重复 succeed 不改变结果。
- [ ] **Step 2: Run tests to verify failure** — `mvn -pl agent-media-service -am test -Dtest=WorkerLeaseServiceTest`。
- [ ] **Step 3: Implement lease and Flyway migrations** — 优先 MySQL 行级锁/租约字段，保留现有任务语义。
- [ ] **Step 4: Run tests and migration validation** — 使用 Compose MySQL 执行 Flyway，确认重复启动不重复建表。
- [ ] **Step 5: Commit** — `git add infra/mysql agent-media-service && git commit -m "feat: isolate media worker with idempotent leases"`。

### Task 7: 容器化、本地部署与运维文档

**Files:**
- Create: `Dockerfile.gateway`
- Create: `Dockerfile.service`
- Create: `docker-compose.app.yml`
- Create: `.env.example`
- Create: `docs/deployment/server-deploy.md`
- Create: `docs/deployment/rollback.md`
- Create: `scripts/build-images.ps1`
- Create: `scripts/smoke-test.ps1`

**Interfaces:**
- `docker compose -f infra/docker-compose.yml -f docker-compose.app.yml up -d` 启动本地完整栈。
- `scripts/smoke-test.ps1` 检查 Nacos、Gateway、各服务健康状态和基础路由。
- 服务器部署文档覆盖 Linux/Docker、Nacos 初始化、环境变量、数据库备份、升级和回滚。

- [ ] **Step 1: Write smoke test script** — 固定健康端点和预期 HTTP 状态。
- [ ] **Step 2: Run smoke test to verify failure** — 在未构建镜像时预期失败且错误可读。
- [ ] **Step 3: Add Dockerfiles and Compose overlay** — 镜像非 root、固定 JVM 参数、健康检查和日志卷。
- [ ] **Step 4: Build and run locally** — `docker compose -f infra/docker-compose.yml -f docker-compose.app.yml up -d --build`，再运行 `scripts/smoke-test.ps1`。
- [ ] **Step 5: Commit** — `git add Dockerfile* docker-compose.app.yml .env.example scripts docs/deployment && git commit -m "ops: add local and server deployment workflow"`。

### Task 8: 全量验证与交付审查

**Files:**
- Modify: `README.md`
- Create: `docs/verification/production-baseline-checklist.md`
- Test: all Maven modules and Compose smoke test.

- [ ] **Step 1: Run Maven verification** — `mvn -DskipTests=false verify`，记录失败模块和依赖冲突。
- [ ] **Step 2: Run Compose verification** — 启动完整栈，检查注册、配置、Gateway 路由、Sentinel 连接、数据库迁移和 SSE。
- [ ] **Step 3: Run security/config scan** — 确认仓库不含真实 Key、密码和本地绝对路径。
- [ ] **Step 4: Update README and checklist** — 写明本地启动、服务器部署、回滚和已知限制。
- [ ] **Step 5: Stop before GitHub push** — 将变更、测试结果和部署方式交给用户验收；只有用户明确批准后才执行 commit/push。
