# agentDemo1_0 项目说明文档

> 文档编号：README9_6　|　生成日期：2026-09-06

---

## 目录

1. [项目概述](#1-项目概述)
2. [技术栈](#2-技术栈)
3. [已实现功能](#3-已实现功能)
4. [项目框架结构](#4-项目框架结构)
5. [核心模块详解](#5-核心模块详解)
6. [类 / 接口 / 方法 关联关系](#6-类--接口--方法-关联关系)
7. [REST API 接口说明](#7-rest-api-接口说明)
8. [一次完整对话的调用流程](#8-一次完整对话的调用流程)
9. [配置说明](#9-配置说明)
10. [测试](#10-测试)

---

## 1. 项目概述

`agentDemo1_0` 是一个基于 **Spring AI + Spring WebFlux** 的 **旅行助手 Agent（智能体）** 演示项目。

用户通过 HTTP 接口向系统提问（例如「北京今天天气怎么样？适合去哪里玩？」），系统会：

1. 将问题交给大语言模型（LLM，当前接入阿里百炼 Qwen）；
2. 由模型自主决定是否调用**工具（Tool）**：
   - `getWeather` —— 通过 Tavily 搜索实时天气；
   - `getAttraction` —— 根据城市和天气推荐旅游景点；
3. 收集工具返回的实时信息后，模型生成最终的中文回答；
4. 回答以 **SSE 流式事件**（或同步 JSON）形式返回给前端，并支持**多轮对话记忆、中断生成、历史查询/清空**。

项目的核心价值在于演示一套**「LLM + 工具调用（Function Calling）+ 流式输出 + 会话记忆」**的最小可运行 Agent 骨架。

---

## 2. 技术栈

| 类别 | 技术 / 版本 | 说明 |
|------|-------------|------|
| 语言 / 运行时 | Java 21 | 使用 record、文本块等新特性 |
| 构建工具 | Maven | `pom.xml`，parent 为 `spring-boot-starter-parent:3.4.12` |
| 应用框架 | Spring Boot 3.4.12 | 自动装配、依赖注入 |
| Web 框架 | **Spring WebFlux**（响应式） | 唯一 Web 栈，返回 `Flux` 实现 SSE；不引入 MVC，避免 `DispatcherServlet` 抢注导致 SSE 退化 |
| AI 框架 | **Spring AI 1.0.0-M6** | `spring-ai-core`（`@Tool` / `ChatClient` / `Advisor`）+ `spring-ai-openai`（OpenAI 兼容 `ChatModel`） |
| LLM 服务 | 阿里百炼 DashScope（OpenAI 兼容模式） | 默认模型 `qwen-turbo` |
| 搜索数据源 | **Tavily Search API** | 天气 / 景点工具的数据来源 |
| 序列化 | Jackson (`jackson-databind`) | SSE 事件 JSON 序列化、Tavily 响应解析 |
| 简化代码 | Lombok | `@Data` / `@Builder` / `@Slf4j` / `@RequiredArgsConstructor` 等 |
| 测试 | JUnit 5 + Mockito + AssertJ | `spring-boot-starter-test` |

**关键依赖坐标（pom.xml）：**

- `org.springframework.boot:spring-boot-starter-webflux`
- `org.springframework.ai:spring-ai-core`
- `org.springframework.ai:spring-ai-openai`
- `org.projectlombok:lombok`
- `com.fasterxml.jackson.core:jackson-databind`
- 测试：`org.springframework.boot:spring-boot-starter-test`

> 说明：`spring-ai-bom:1.0.0-M6` 通过 `dependencyManagement` 统一管理 Spring AI 依赖版本；仓库配置了阿里云 Maven 镜像；`maven-compiler-plugin` 开启 `<parameters>true</parameters>` 以保留方法参数名，供 `@Tool` 反射生成 JSON Schema。

---

## 3. 已实现功能

1. **流式对话（SSE）**：`POST /api/chat` 以 `text/event-stream` 逐段推送模型输出，前端实时渲染。
2. **同步对话**：`POST /api/chat/sync` 一次性返回完整回答 JSON。
3. **工具调用（Function Calling）**：
   - `getWeather`：查询城市实时天气（温度、天气状况、湿度、风速）。
   - `getAttraction`：根据城市 + 天气推荐旅游景点并给出理由。
   - 两个工具均通过 Tavily 搜索接口获取实时数据，入参 DTO 由 Spring AI 自动生成 JSON Schema。
4. **多轮对话记忆**：基于 `InMemoryChatMemory` + `MessageChatMemoryAdvisor`，同一 `sessionId` 内上下文连续；未传 `sessionId` 时使用默认会话 `chat-default`。
5. **中断生成**：`POST /api/chat/stop` 停止当前会话的流式生成，并把已生成的部分内容写回会话记忆，避免上下文断层。
6. **历史查询 / 清空**：`GET /api/chat/history` 查询会话历史（格式化「我: / 助手:」标签），`DELETE /api/chat/history` 清空。
7. **CORS 跨域**：`WebConfig` 注册响应式 CORS 过滤器，允许本地前端（`localhost:*` / `127.0.0.1:*`）访问 `/api/**`。
8. **统一事件协议**：定义 `ChatEventTypeEnum`（DATA 1001 / STOP 1002 / PARAM 1003 / ERROR 1004），流末尾必定补发 STOP 事件。
9. **系统提示词**：`src/main/resources/system_prompt` 定义 Agent 行为规则（中文输出、按步骤调用工具、禁止输出中间思考过程）。
10. **契约语义单测**：`ChatServiceImplTest` 覆盖空问题、空流、历史格式化、会话键规则等对外行为。

---

## 4. 项目框架结构

```
agentDemo1_0
├── pom.xml
├── README9_6.md                         ← 本文档
└── src
    ├── main
    │   ├── java/com/bbb/exercise/agentdemo1_0
    │   │   ├── AgentDemo10Application.java     # 启动类
    │   │   ├── agent
    │   │   │   └── AgentRunner.java            # Agent 执行器（LLM 调用核心）
    │   │   ├── config
    │   │   │   ├── LlmConfig.java              # LLM / ChatClient / ChatMemory 装配
    │   │   │   ├── TavilyConfig.java           # Tavily WebClient 装配
    │   │   │   └── WebConfig.java              # WebFlux CORS 配置
    │   │   ├── controller
    │   │   │   └── ChatController.java         # REST 控制器
    │   │   ├── dto
    │   │   │   └── ChatRequest.java            # 请求参数 DTO
    │   │   ├── enums
    │   │   │   └── ChatEventTypeEnum.java      # 事件类型枚举
    │   │   ├── service
    │   │   │   ├── ChatService.java            # 服务接口
    │   │   │   └── impl
    │   │   │       └── ChatServiceImpl.java    # 服务实现
    │   │   ├── support
    │   │   │   └── ConversationKeys.java       # 会话键工具类
    │   │   ├── tools
    │   │   │   ├── weatherTool.java            # 天气查询工具
    │   │   │   └── attractionTool.java         # 景点推荐工具
    │   │   └── vo
    │   │       └── ChatEventVO.java            # 事件视图对象
    │   └── resources
    │       ├── application.yml                 # 主配置
    │       └── system_prompt                   # 系统提示词
    └── test/java/com/bbb/exercise/agentdemo1_0
        ├── AgentDemo10ApplicationTests.java    # 上下文加载测试
        └── ChatServiceImplTest.java            # 服务契约单测
```

分层说明：

| 层 | 包 | 职责 |
|----|----|----|
| 启动 | `（根包）` | `AgentDemo10Application` 启动 Spring Boot |
| 控制器 | `controller` | 接收 HTTP 请求，参数校验与响应封装 |
| 服务 | `service` / `service.impl` | 业务编排（流式/同步/停止/历史），接口与实现分离 |
| Agent | `agent` | 封装 Spring AI `ChatClient` 的调用（阻塞与流式），是模型交互的唯一入口 |
| 工具 | `tools` | 供 LLM 调用的 `@Tool` 方法，访问 Tavily |
| 配置 | `config` | Bean 装配（LLM、Tavily、CORS） |
| 模型对象 | `dto` / `vo` / `enums` | 请求体、事件体、事件类型 |
| 工具类 | `support` | 会话键解析 |

---

## 5. 核心模块详解

### 5.1 启动类 `AgentDemo10Application`

`@SpringBootApplication` 注解的普通启动类，`main` 方法调用 `SpringApplication.run`。无额外配置。

### 5.2 配置层 `config`

#### `LlmConfig`（LLM 装配，核心）

手动组装 OpenAI 兼容模型，绕开 Spring AI 自动装配（因此在 `application.yml` 里的部分 chat 配置不生效）：

| Bean | 说明 |
|------|------|
| `ChatMemory chatMemory()` | `InMemoryChatMemory`，内存版会话记忆 |
| `ChatClient qwenChatClient(ChatMemory)` | 通过 `OpenAiApi`（api-key / base-url / `/chat/completions`）+ `OpenAiChatOptions`（model、`streamUsage(true)`）构建 `OpenAiChatModel`，再 `ChatClient.builder` 装配两个默认 Advisor：`SimpleLoggerAdvisor`（日志）、`MessageChatMemoryAdvisor`（记忆） |

#### `TavilyConfig`

注册 `WebClient` Bean `tavilyWebClient`，设置 `base-url`、`Authorization: Bearer <api-key>`、`Content-Type: application/json` 默认头。两个工具均注入该客户端。

#### `WebConfig`

实现 `WebFluxConfigurer`，注册 `CorsWebFilter`，作用于 `/api/**`，允许 `localhost:*` / `127.0.0.1:*`，方法 `GET/POST/DELETE/OPTIONS`。

### 5.3 工具层 `tools`

两个工具结构对称，均：

- `@Component` 注册为 Spring Bean；
- 注入 `tavilyWebClient`（`@Qualifier`）；
- 通过 `@Value` 注入 `tavily.search-depth` / `max-results` / `include-answer`；
- 定义入参 record（`WeatherRequest` / `AttractionRequest`），字段上用 `@JsonPropertyDescription` 描述，供 Spring AI 生成 JSON Schema；
- `@Tool(description=...)` 标注的方法（`getWeather` / `getAttraction`）即模型可调用的工具；
- 内部调用 Tavily `/search`，请求体为 `{query, search_depth, max_results, include_answer}`；
- 解析响应：**优先取 `answer` 字段，取不到则回退拼接 `results[].content`**。

| 工具 | 方法 | 入参 | 用途 |
|------|------|------|------|
| `weatherTool` | `getWeather(WeatherRequest)` | `city` | 查询城市实时天气 |
| `attractionTool` | `getAttraction(AttractionRequest)` | `city`、`weather`（可选） | 根据城市+天气推荐景点 |

### 5.4 Agent 层 `agent.AgentRunner`

`@Component`，是业务层与 Spring AI 之间的唯一桥梁。构造时注入 `qwenChatClient`、两个工具、`ChatMemory`，并读取 `classpath:system_prompt` 为系统提示词。

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `chat(question, conversationId)` | `String` | 阻塞式对话：`.prompt().system(...).advisors(...).user(...).tools(...).call()`，返回最终答案并打 token/耗时日志 |
| `stream(question, conversationId)` | `Flux<ChatResponse>` | 流式对话：同上但用 `.stream()`，末尾/出错打日志，`collectUsage` 汇总 token（覆盖式，兼容累计上报） |
| `savePartialResponse(conversationId, content)` | `void` | 中断时把已生成部分写入 `ChatMemory`（存为 `AssistantMessage`） |
| `history(conversationId)` | `List<Message>` | 读取会话历史（`chatMemory.get(..., MAX_VALUE)`） |
| `clearHistory(conversationId)` | `void` | 清空会话历史 |

> 记忆键：通过 `MessageChatMemoryAdvisor` 的 `CHAT_MEMORY_CONVERSATION_ID_KEY` 参数传入 `conversationId`。

### 5.5 服务层 `service` / `service.impl`

#### 接口 `ChatService`

| 方法 | 说明 |
|------|------|
| `Flux<ChatEventVO> chat(question, sessionId)` | 流式对话 |
| `String chatSync(question, sessionId)` | 同步对话 |
| `void stop(sessionId)` | 停止生成 |
| `List<String> history(sessionId)` | 查询历史 |
| `void clear(sessionId)` | 清空历史 |

#### 实现 `ChatServiceImpl`

`@Service`，持有 `AgentRunner`，并维护一个 `static` 的 `GENERATING`（`ConcurrentHashMap<String,Boolean>`）标记每个会话是否正在生成。

`chat` 的响应式管道（核心逻辑）：

```
agentRunner.stream(question, conversationId)
  .doFirst(置 GENERATING=true)
  .doOnComplete/doOnError(清 GENERATING)
  .doOnCancel(清 GENERATING + savePartialResponse 保存已生成部分)
  .takeWhile(GENERATING 为 true)      // stop() 把标记移除后流即被截断
  .map(ChatResponse → ChatEventVO(DATA, 文本增量))
  .filter(eventData != null)
  .concatWith(STOP_EVENT)              // 末尾必定补发 1002
  .onErrorResume(→ ERROR 事件)
```

其他方法：

- `chatSync`：空问题返回「问题不能为空」，否则委托 `agentRunner.chat`。
- `stop`：`GENERATING.remove(conversationId)`（幂等）。
- `history`：遍历消息，`UserMessage` 加「我: 」前缀，无工具调用的 `AssistantMessage` 加「助手: 」前缀，过滤空文本。
- `clear`：委托 `agentRunner.clearHistory`。

### 5.6 模型对象 `dto` / `vo` / `enums`

- `ChatRequest`（`@Data`）：`question`、`sessionId`。
- `ChatEventVO`（`@Data @Builder`）：`eventData`（Object）、`eventType`（int），SSE 序列化为一行 JSON。
- `ChatEventTypeEnum`（`@Getter`）：`DATA(1001)`、`STOP(1002)`、`PARAM(1003，预留)`、`ERROR(1004，项目扩展)`。

### 5.7 工具类 `support.ConversationKeys`

- `PREFIX = "chat-"`、`DEFAULT_ID = "chat-default"`。
- `resolve(sessionId)`：空/空白 → `chat-default`；非空 → `chat-` + `trim(sessionId)`。

---

## 6. 类 / 接口 / 方法 关联关系

### 6.1 依赖关系图

```
                         ┌────────────────────┐
                         │ ChatController      │  (controller)
                         │ - chatService       │
                         └─────────┬──────────┘
                                   │ 依赖 (接口)
                         ┌─────────▼──────────┐
                         │ ChatService (接口)  │  (service)
                         └─────────▲──────────┘
                                   │ 实现
                         ┌─────────┴──────────┐
                         │ ChatServiceImpl    │  (service.impl)
                         │ - agentRunner      │
                         └─────────┬──────────┘
                                   │ 依赖
                         ┌─────────▼──────────┐
                         │ AgentRunner        │  (agent)
                         │ - chatClient       │◄──────── qwenChatClient (LlmConfig)
                         │ - weatherTool      │◄──────── weatherTool
                         │ - attractionTool   │◄──────── attractionTool
                         │ - chatMemory       │◄──────── chatMemory (LlmConfig)
                         │ - systemPrompt     │◄──────── classpath:system_prompt
                         └──────┬─────────────┘
                                │ 调用
              ┌─────────────────┴──────────────────┐
              │                                     │
     ┌────────▼────────┐                  ┌────────▼────────┐
     │ weatherTool      │                  │ attractionTool  │
     │ - tavilyWebClient│                  │ - tavilyWebClient│
     └────────┬────────┘                  └────────┬────────┘
              │ (注入 @Qualifier)                  │
              └──────────────┬─────────────────────┘
                             │
                    ┌────────▼────────┐
                    │ tavilyWebClient │  (TavilyConfig)
                    └────────┬────────┘
                             │ HTTP POST /search
                    ┌────────▼────────┐
                    │  Tavily API      │
                    └─────────────────┘
```

### 6.2 依赖注入流向（Spring 容器装配顺序）

1. `LlmConfig` 提供 `ChatMemory`、`ChatClient`（`qwenChatClient`）两个 Bean。
2. `TavilyConfig` 提供 `WebClient`（`tavilyWebClient`）Bean。
3. `weatherTool` / `attractionTool` 通过 `@Qualifier("tavilyWebClient")` 注入 `WebClient`。
4. `AgentRunner` 构造注入 `qwenChatClient`、`weatherTool`、`attractionTool`、`ChatMemory`，并 `@Value` 读取系统提示词文件。
5. `ChatServiceImpl` 构造注入 `AgentRunner`（`@RequiredArgsConstructor`）。
6. `ChatController` 构造注入 `ChatService` 接口（实际注入 `ChatServiceImpl`）。

### 6.3 关键关联汇总表

| 调用方 | 被调用方 | 关系 |
|--------|----------|------|
| `ChatController` | `ChatService` | 依赖注入（接口） |
| `ChatServiceImpl` | `ChatService` | 实现（implements） |
| `ChatServiceImpl` | `AgentRunner` | 依赖注入（`@RequiredArgsConstructor`） |
| `AgentRunner` | `ChatClient`（`qwenChatClient`） | 依赖注入 |
| `AgentRunner` | `weatherTool` / `attractionTool` | 依赖注入 |
| `AgentRunner` | `ChatMemory` | 依赖注入 |
| `LlmConfig` | `ChatClient` / `ChatMemory` | 生产 Bean（`@Bean`） |
| `TavilyConfig` | `WebClient`（`tavilyWebClient`） | 生产 Bean |
| `weatherTool` / `attractionTool` | `WebClient` | 依赖注入（`@Qualifier`） |
| `ChatEventVO` | `ChatEventTypeEnum` | 使用（`eventType` 取枚举 `value`） |
| `ChatController` / `ChatServiceImpl` | `ConversationKeys` | 静态方法调用 `resolve()` |

---

## 7. REST API 接口说明

基础路径：`/api/chat`

| 方法 | 路径 | 请求 | 响应 | 说明 |
|------|------|------|------|------|
| POST | `/api/chat` | `ChatRequest` | `text/event-stream`（`ChatEventVO` 序列化行） | 流式对话（SSE） |
| POST | `/api/chat/sync` | `ChatRequest` | `application/json` `{"answer": "..."}` | 同步对话 |
| POST | `/api/chat/stop` | `?sessionId=` | `{"stopped":true,"sessionId":"..."}` | 中断生成 |
| GET | `/api/chat/history` | `?sessionId=`（可选） | `{"history":[...],"count":N}` | 查询历史 |
| DELETE | `/api/chat/history` | `?sessionId=`（可选） | `{"cleared":true}` | 清空历史 |

**请求体 `ChatRequest`：**

```json
{ "question": "北京今天天气怎么样？适合去哪里玩？", "sessionId": "mem-1" }
```

**SSE 事件示例（`ChatEventVO` JSON）：**

```json
{"eventData":"北京今天晴","eventType":1001}
{"eventData":"，26℃。","eventType":1001}
{"eventData":null,"eventType":1002}
```

事件类型：`1001` DATA（文本增量）、`1002` STOP（流结束）、`1003` PARAM（预留）、`1004` ERROR（错误）。

---

## 8. 一次完整对话的调用流程

以流式对话为例（`POST /api/chat`）：

1. **请求进入** `ChatController.chat`，用 `ConversationKeys.resolve(sessionId)` 得到内部 `conversationId`。
2. **调用** `ChatServiceImpl.chat(question, sessionId)`：
   - 空问题直接返回一个 ERROR 事件；
   - 否则进入 `agentRunner.stream(...)`。
3. **`AgentRunner.stream`** 通过 `ChatClient` 构建请求：系统提示词 + 记忆 Advisor + 用户问题 + 两个工具，`.stream()` 发起流式调用。
4. **Spring AI / Qwen** 判断是否需要调用工具：
   - 需要天气 → 调用 `weatherTool.getWeather`（内部走 Tavily）；
   - 需要景点 → 调用 `attractionTool.getAttraction`（内部走 Tavily）；
   - 工具结果回传模型后，模型生成最终回答。
5. **模型输出流**逐段返回 `ChatResponse`，`ChatServiceImpl` 将其映射为 `ChatEventVO(DATA)`。
6. **末尾**拼接一个 `STOP` 事件（`concatWith(STOP_EVENT)`），流结束。
7. **中断场景**：`stop()` 移除 `GENERATING` 标记 → `takeWhile` 截断流 → `doOnCancel` 保存部分回复到记忆。

---

## 9. 配置说明

核心配置见 `src/main/resources/application.yml`：

| 配置项 | 值 | 说明 |
|--------|----|----|
| `server.port` | `18080` | 服务端口（前端代理与 CORS 直连均指向它） |
| `spring.ai.openai.api-key` | `sk-...` | 阿里百炼密钥（建议环境变量注入，勿明文入库） |
| `spring.ai.openai.base-url` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | OpenAI 兼容端点 |
| `spring.ai.openai.chat.options.model` | `qwen-turbo` | 模型（可换 `qwen-plus` / `qwen-max`） |
| `tavily.api-key` | `tvly-...` | Tavily 密钥 |
| `tavily.base-url` | `https://api.tavily.com` | Tavily 端点 |
| `tavily.search-depth` | `advanced` | 搜索深度 |
| `tavily.max-results` | `5` | 最大结果数 |
| `tavily.include-answer` | `true` | 是否返回 AI 摘要 answer |
| `logging.level.org.springframework.ai` | `DEBUG` | 观察多轮工具调用 |

**系统提示词**（`system_prompt`）要点：中文助手、可用工具 `getWeather` / `getAttraction`、按步骤调用工具、等待工具结果、禁止输出 Thought/Action/Observation 中间过程。

> 注意：`application.yml` 中的 `chat.options` 注释已说明——因本项目在 `LlmConfig` 手动构建 `OpenAiChatModel`，绕过了自动装配，所以 `stream-usage` 需在代码中 `.streamUsage(true)` 设置。

---

## 10. 测试

| 测试类 | 类型 | 覆盖 |
|--------|------|------|
| `AgentDemo10ApplicationTests` | `@SpringBootTest` | 上下文能否正常加载 |
| `ChatServiceImplTest` | 纯 Mock 单测（Mockito + AssertJ） | 空问题→1004；空流→恰好一个 1002；历史标签与过滤；停止幂等；清空委托；会话键规则 |

`ChatServiceImplTest` 不依赖 LLM / 网络 / Spring 容器，通过 `mock(AgentRunner.class)` 守护对外契约语义。

---

## 附：代码清单索引

| 类 / 接口 | 全限定名 | 类型 |
|-----------|----------|------|
| `AgentDemo10Application` | `com.bbb.exercise.agentdemo1_0.AgentDemo10Application` | 启动类 |
| `AgentRunner` | `...agent.AgentRunner` | `@Component` |
| `ChatController` | `...controller.ChatController` | `@RestController` |
| `ChatService` | `...service.ChatService` | 接口 |
| `ChatServiceImpl` | `...service.impl.ChatServiceImpl` | `@Service` |
| `ChatRequest` | `...dto.ChatRequest` | DTO |
| `ChatEventVO` | `...vo.ChatEventVO` | VO |
| `ChatEventTypeEnum` | `...enums.ChatEventTypeEnum` | 枚举 |
| `ConversationKeys` | `...support.ConversationKeys` | 工具类 |
| `LlmConfig` | `...config.LlmConfig` | `@Configuration` |
| `TavilyConfig` | `...config.TavilyConfig` | `@Configuration` |
| `WebConfig` | `...config.WebConfig` | `@Configuration` |
| `weatherTool` | `...tools.weatherTool` | `@Component` |
| `attractionTool` | `...tools.attractionTool` | `@Component` |
