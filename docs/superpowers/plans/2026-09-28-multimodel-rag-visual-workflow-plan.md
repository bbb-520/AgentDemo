# 多模型视觉创作与长期偏好记忆实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在模块化单体中实现多模型选择、RAG 视觉偏好、可暂停图片对话、变体/Remix 与 Bobo World 基础互动。

**Architecture:** 以 `model`、`memory`、`conversation`、`generation`、`world` 包隔离职责，保留现有 Spring Boot 部署单元。先建立 provider 与状态机的纯 Java 契约，再接数据库和 Redis，最后接入 WebFlux API 与 React 状态机。

**Tech Stack:** Java 21, Spring Boot WebFlux, Spring AI, JDBC/MyBatis-Plus, Redis Stack, MySQL, React 18, TypeScript, Vite, Vitest/现有测试工具。

**Spec:** `docs/superpowers/specs/2026-09-28-multimodel-rag-visual-workflow-design.md`

## Global Constraints

- 不修改 P0/P1 网络、安全和部署配置。
- `/api/chat` 兼容旧字段和旧事件。
- 纯图片请求必须进入 `WAITING_FOR_INSTRUCTION`，不能隐式消耗图片生成额度。
- 公开 World DTO 不包含内部 prompt、provider secret 或任务错误详情。
- 没有 embedding Provider 时必须使用结构化偏好匹配降级。

## Review Focus

- 用户没有图片但发送文字：应正常回答或等待澄清，而不是提示“请先上传图片”。
- 用户只上传图片：应生成 Scene Card 并等待指令，不自动生成图片。
- Provider 未配置或调用失败：应返回可恢复的错误并保留会话上下文。
- Redis/embedding 不可用：主流程仍可用结构化偏好匹配。
- 公开作品详情：不能泄露内部 prompt、密钥或模型调用错误。

### Task 1: Provider registry and user model profiles

**Files:**
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/model/ModelProvider.java`
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/model/ModelCapability.java`
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/model/ModelProfile.java`
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/model/ModelProviderRegistry.java`
- Modify: `src/main/java/com/bbb/exercise/agentdemo1_0/auth/UserApiKeyService.java`
- Create: `src/test/java/com/bbb/exercise/agentdemo1_0/model/ModelProviderRegistryTest.java`

**Interfaces:**
- `ModelProviderRegistry.resolve(provider, capability, model)` returns a provider adapter or a typed configuration error.
- `ModelProfile` exposes provider, capability, model and enabled state; no secret value.

- [ ] Write failing tests for GPT/Gemini/Qwen/GLM/HY registration, unsupported capability, and masked profile output.
- [ ] Run the focused test and verify it fails because the registry does not exist.
- [ ] Implement provider enum, capability enum, profile value object, registry and an OpenAI-compatible adapter descriptor.
- [ ] Run focused tests and then the existing auth tests.

### Task 2: Conversation intent and image-waiting state machine

**Files:**
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/conversation/ConversationIntent.java`
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/conversation/ConversationTurnState.java`
- Modify: `src/main/java/com/bbb/exercise/agentdemo1_0/chat/ChatService.java`
- Modify: `src/main/java/com/bbb/exercise/agentdemo1_0/dto/ChatRequest.java`
- Modify: `src/main/java/com/bbb/exercise/agentdemo1_0/enums/ChatEventTypeEnum.java`
- Create: `src/test/java/com/bbb/exercise/agentdemo1_0/chat/ConversationIntentTest.java`

**Interfaces:**
- `ConversationIntent classify(questionPresent, imagePresent, hasPendingSceneCard)` returns `DESCRIBE`, `ANSWER`, `GENERATE`, `REMIX`, or `WAIT_FOR_INSTRUCTION`.
- `ChatService.chat` accepts zero or one attachment and emits `SCENE_CARD` plus `WAITING_FOR_INSTRUCTION` for image-only input.

- [ ] Write failing tests for text-only, image-only, image+instruction, and Remix-from-pending-context paths.
- [ ] Run focused tests and verify the current “must upload image” behavior fails the new expectations.
- [ ] Implement the minimal classifier and event emission while preserving old event values.
- [ ] Run backend tests and verify old image-job contract tests remain green.

### Task 3: Vision context and generation command boundaries

**Files:**
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/generation/SceneCard.java`
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/generation/GenerationCommand.java`
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/generation/GenerationOrchestrator.java`
- Modify: `src/main/java/com/bbb/exercise/agentdemo1_0/image/ImageJobService.java`
- Create: `src/test/java/com/bbb/exercise/agentdemo1_0/generation/GenerationOrchestratorTest.java`

**Interfaces:**
- `GenerationOrchestrator.analyze(asset, modelProfile)` returns a `SceneCard`.
- `GenerationOrchestrator.generate(command, modelProfile, preferenceContext)` returns a generation request with one or more variants.

- [ ] Write failing tests for Scene Card persistence, generate vs remix command selection, and provider error preservation.
- [ ] Run focused tests and verify the orchestration layer is missing.
- [ ] Implement orchestration behind provider interfaces; keep existing worker as the execution boundary.
- [ ] Run the full backend test suite.

### Task 4: RAG vision preference memory

**Files:**
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/memory/VisionMemory.java`
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/memory/VisionMemoryService.java`
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/memory/PreferenceContext.java`
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/memory/PreferenceMemoryController.java`
- Modify: `src/main/resources/schema.sql`
- Create: `src/test/java/com/bbb/exercise/agentdemo1_0/memory/VisionMemoryServiceTest.java`

**Interfaces:**
- `VisionMemoryService.remember(identity, memory)` stores structured memory and optional embedding.
- `VisionMemoryService.retrieve(identity, query, limit)` returns ranked `PreferenceContext` entries with source and score.
- `VisionMemoryService.delete(identity, memoryId)` and `pause(identity, paused)` support user control.

- [ ] Write failing tests for remember/retrieve/delete/pause and embedding-unavailable fallback.
- [ ] Run focused tests and verify the service is missing.
- [ ] Implement MySQL structured memory plus Redis vector adapter with lexical/structured fallback.
- [ ] Add authenticated memory endpoints and run backend tests.

### Task 5: Generation variants, Remix and World reactions

**Files:**
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/generation/GenerationVariantService.java`
- Create: `src/main/java/com/bbb/exercise/agentdemo1_0/world/WorldReactionController.java`
- Modify: `src/main/java/com/bbb/exercise/agentdemo1_0/bobo/BoboWorldService.java`
- Modify: `src/main/resources/schema.sql`
- Create: `src/test/java/com/bbb/exercise/agentdemo1_0/generation/GenerationVariantServiceTest.java`

**Interfaces:**
- `GenerationVariantService.createVariants(command, count)` creates bounded variants with parent linkage.
- `GenerationVariantService.remix(identity, variantId, instruction)` creates a child generation command.
- World reactions are idempotent per `(tenant, user, item, type)`.

- [ ] Write failing tests for count clamping, parent linkage, Remix ownership, and idempotent reaction toggles.
- [ ] Run focused tests and verify the new services are absent.
- [ ] Implement generation and reaction persistence with public DTO filtering.
- [ ] Run backend tests and schema validation.

### Task 6: React model selector, waiting state, memory and variant UI

**Files:**
- Modify: `src/types.ts`
- Modify: `src/lib/api.ts`
- Modify: `src/hooks/useConversations.ts`
- Modify: `src/components/Composer.tsx`
- Create: `src/components/ModelSelector.tsx`
- Create: `src/components/SceneCard.tsx`
- Create: `src/components/GenerationVariants.tsx`
- Create: `src/components/MemorySettings.tsx`
- Create: `src/test/...` using the repository's chosen frontend test runner

- [ ] Add failing tests for text-only send, image-only waiting, provider selection and variant selection.
- [ ] Run frontend tests and verify the current composer rejects text-only requests.
- [ ] Implement the smallest state-machine changes and API clients.
- [ ] Run typecheck, frontend tests and production build.

### Task 7: Documentation and local verification

**Files:**
- Modify: `README.md` in both projects
- Create: `docs/superpowers/verification/2026-09-28-local-verification.md`

- [ ] Document model configuration, memory controls, new chat states and local setup.
- [ ] Run backend tests, frontend typecheck, frontend tests and build.
- [ ] Record exact commands and results; do not deploy remotely.
