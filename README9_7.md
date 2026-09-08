# agentDemo1_0 项目说明文档

> 文档编号：**README9_7**　|　生成日期：**2026-09-08**　|　上一版：README9_6（2026-09-06）
>
> 本文在 README9_6 基础上重写，覆盖 9/6 → 9/8 期间的架构演进：
> **Spring AI 升级 1.0.5 GA、Agent 改为「两阶段直播」、工具层改为自动扫描 + 多源降级、
> 会话记忆升级为「内存 + Redis 二级方案 + LLM 摘要压缩」**。

---

## 目录

1. [项目概述](#1-项目概述)
2. [技术栈](#2-技术栈)
3. [已实现功能与技术亮点](#3-已实现功能与技术亮点)
4. [项目结构](#4-项目结构)
5. [核心模块详解](#5-核心模块详解)
6. [关键实现原理](#6-关键实现原理)
7. [REST API 接口说明](#7-rest-api-接口说明)
8. [SSE 事件协议（直播事件）](#8-sse-事件协议直播事件)
9. [一次完整对话的时序](#9-一次完整对话的时序)
10. [配置说明](#10-配置说明)
11. [测试](#11-测试)
12. [对比 README9_6 的改进清单](#12-对比-readme9_6-的改进清单)
13. [已知限制与后续 TODO](#13-已知限制与后续-todo)
14. [附：代码清单索引](#14-附代码清单索引)

---

## 1. 项目概述

`agentDemo1_0` 是一个基于 **Spring AI 1.0.5 + Spring WebFlux** 的**旅行助手 Agent** 演示项目，
演示一套可运行的「**LLM + Function Calling + 直播式流式输出 + 二级会话记忆**」骨架。

用户提问（例：「北京今天天气怎么样？适合去哪里玩？」）后，系统会：

1. 由模型**自主决策**是否调用工具：`getWeather`（实时天气）、`getAttraction`（景点推荐）；
2. 工具结果**回灌模型**，模型可继续多轮调用，直到产出最终答案；
3. 全过程以 **SSE 事件流**「直播」给前端：会话信息 → 思考 → 工具开始 → 工具结果 → 答案分块 → 用量统计 → 停止；
4. 问答内容**双写**到「内存窗口（短时上下文）」与「Redis（长期历史）」，并可由 LLM 对过期历史做**摘要压缩**。

相比上一版，本项目已从「能跑通 Function Calling 的最小 Demo」演进为
「**具备可观测直播、可插拔工具、可降级数据源、可持久化记忆**的 Agent 骨架」。

---

## 2. 技术栈

| 类别 | 技术 / 版本 | 说明 |
|------|-------------|------|
| 语言 / 运行时 | Java 21 | `record`、文本块、`switch` 表达式等 |
| 构建工具 | Maven（带 Wrapper 3.9.9） | parent：`spring-boot-starter-parent:3.4.12` |
| 应用框架 | Spring Boot **3.4.12** | 自动装配 + 依赖注入 |
| Web 框架 | **Spring WebFlux**（响应式，唯一 Web 栈） | `Flux` + `text/event-stream` 实现 SSE；禁止引入 MVC，否则 `DispatcherServlet` 抢注导致 SSE 退化 |
| AI 框架 | **Spring AI 1.0.5（GA）** | `spring-ai-client-chat`（`ChatClient` / `Advisor` / `ChatMemory`）+ `spring-ai-openai`（OpenAI 兼容 `ChatModel`） |
| LLM 服务 | 阿里百炼 DashScope（OpenAI 兼容模式） | 默认模型 `qwen-turbo`，可切 `qwen-plus` / `qwen-max` |
| 响应式编程 | **Project Reactor** | `Flux.defer` / `concat` / `takeWhile` / `delayElements` / `Schedulers.boundedElastic()` |
| 天气数据源 | **Open-Meteo**（首选，免费免 Key、结构化）+ **Tavily**（兜底） | 责任链自动降级 |
| 搜索数据源 | **Tavily Search API** | 景点推荐、天气兜底 |
| 长期存储 | **Redis**（普通版，非 Redis Stack）+ `StringRedisTemplate` | Sorted Set 存会话历史，Lettuce 连接池 |
| 序列化 | Jackson（`jackson-databind` + `jackson-datatype-jsr310`） | 会话记忆专用 `ObjectMapper`（ISO-8601 时间、禁用 defaultTyping 规避 RCE） |
| 工具库 | Hutool 5.8.32 + 自封装 `utils` 包 | `StringUtils extends StrUtil`、断言、集合、分页树等 |
| 简化代码 | Lombok | `@Data` / `@Builder` / `@Slf4j` / `@RequiredArgsConstructor` / `@Getter @Setter` |
| 测试 | JUnit 5 + Mockito + AssertJ + Reactor Test | `spring-boot-starter-test` |

**关键依赖坐标（`pom.xml`）：**

```
spring-boot-starter-webflux          # 唯一 Web 栈
spring-ai-client-chat                # ChatClient / Advisor / ChatMemory（1.0 GA 后由 spring-ai-core 拆分而来）
spring-ai-openai                     # OpenAiChatModel（指向 DashScope compatible-mode）
spring-boot-starter-data-redis       # 二级记忆存储
org.apache.commons:commons-pool2     # Lettuce 连接池必需（starter 不传递引入）
com.fasterxml.jackson.datatype:jackson-datatype-jsr310  # Instant 序列化
cn.hutool:hutool-all:5.8.32          # 工具库底座
org.projectlombok:lombok
spring-boot-starter-test
```

> `spring-ai-bom:${spring-ai.version}`（1.0.5）统一管版本；
> `maven-compiler-plugin` 开启 `<parameters>true</parameters>` 保留方法参数名，供 `@Tool` 反射生成 JSON Schema；
> 仓库配置阿里云 Maven 镜像。

---

## 3. 已实现功能与技术亮点

### 3.1 功能清单

| # | 功能 | 实现入口 |
|---|------|----------|
| 1 | **流式对话（SSE 直播）** | `POST /api/chat` → `AgentRunner#stream`，返回统一事件流 |
| 2 | 同步对话 | `POST /api/chat/sync` → `AgentRunner#chat`（走 `ChatClient` 默认 Advisor + 工具） |
| 3 | 工具调用（Function Calling） | `getWeather` / `getAttraction`，由 `ToolConfig` 自动扫描注册 |
| 4 | 多轮工具循环（最多 5 轮） | `AgentRunner#runTurn` 递归 + `MAX_TOOL_ROUNDS` |
| 5 | 思考过程可见（REASONING） | `ReasoningSplitter` 解析「思考：」行 |
| 6 | 工具生命周期事件（STARTED/RESULT/FAILED） | `AgentRunner#executeTool` |
| 7 | 用量统计事件（USAGE） | 多轮 token 累加 + 耗时 |
| 8 | 中断生成 + 部分内容回写 | `POST /api/chat/stop` + `takeWhile` 截断 + `savePartialResponse` 双写 |
| 9 | 同步/流式双路径记忆一致 | 流式路径显式复刻 `MessageChatMemoryAdvisor` 语义 |
| 10 | **二级会话记忆**（内存窗口 + Redis 全量） | `MessageWindowChatMemory` + `RedisChatMemoryRepository` 双写、惰性回填 |
| 11 | 历史分页查询 / 全量查询 | `GET /api/chat/{sessionId}/messages`、`/messages/all` |
| 12 | **LLM 摘要压缩**（自动调度 + 手动触发） | `MemoryCompressionService`、`POST /{sessionId}/summarize` |
| 13 | 历史查询 / 清空（双清） | `GET` / `DELETE /api/chat/history` |
| 14 | 天气多数据源降级 | `WeatherService` 责任链：Open-Meteo → Tavily |
| 15 | 工具零接线扩展 | `ToolConfig` 扫描 `@Tool` 方法自动生成 `ToolCallback` |
| 16 | CORS 跨域 | `WebConfig#corsWebFilter`，作用于 `/api/**` |
| 17 | 参数校验 / 空值防御 | `AssertUtils` + 全链路 `StringUtils` 空安全处理 |

### 3.2 七个技术亮点

**① 两阶段直播架构（本项目最核心的设计）**

不再用 `ChatClient.stream()` 一把梭，而是拆成：

- **决策轮（非流式）**：`ChatModel.call()` 拿完整响应。因为流式下 `tool_call` 的 `arguments` JSON
  可能被拆包/截断，导致工具无法解析——非流式保证参数**必然完整**。
- **答案回放（伪流式）**：拿到最终答案后按 `12 字 / 30ms` 切块推送，
  前端观感与真流式一致。

这一设计**同时解决了「工具参数完整性」与「前端渐进渲染体验」**两个互相冲突的诉求。

**② 手动工具循环 + 协议级接管**

`decisionOptions()` 显式设置 `internalToolExecutionEnabled(false)`，
由 `AgentRunner` 自己执行工具并把 `ToolResponseMessage` 回灌上下文。
代价是要自己管循环，收益是**每一步都能产出可观测的直播事件**（工具名、参数、结果、失败原因）。

**③ 自愈式工具调用**

| 异常场景 | 处理 |
|----------|------|
| `arguments` 不是合法 JSON（被截断） | `isCompleteToolCall()` 校验（Jackson `readTree`）→ 发 `TOOL_CALL_FAILED` → **把错误文本回灌模型，让模型下一轮自我修正重试** |
| 工具未注册 | 回灌「未找到工具」，让模型换路径 |
| 工具执行抛异常 | 回灌「工具执行失败：xxx」，模型可据此生成兜底回答 |
| 模型陷入死循环 | `MAX_TOOL_ROUNDS=5` 硬终止，发 `ERROR` 事件 |

原则：**工具失败不等于对话失败**，错误作为"上下文"回灌模型，最大化任务完成率。

**④ 二级会话记忆 + 惰性回填**

```
一级（短时）：MessageWindowChatMemory（50 条，内存）  → 供 LLM 实时上下文，低延迟
二级（长时）：Redis Sorted Set（全量，TTL 7 天）      → 持久化 / 分页查询 / 摘要压缩
             ↑ 双写          ↓ 惰性回填（内存未命中时取最近 N 条）
```
解决了「进程重启即失忆」与「无限增长撑爆内存」两个问题。

**⑤ LLM 摘要压缩**

空闲 10 分钟 + 历史 > 10 条 触发，LLM 生成 ≤200 字摘要，
**原文保留 + 摘要作为新 SystemMessage 追加**（非覆盖式删除），
并用 `metadata.summary=true` 防重复压缩，下次只压缩「上一条摘要之后的新积累」。

**⑥ 面向 Spring AI 2.0 的前瞻设计**

`AdvancedRedisChatMemoryRepository` 的方法签名**对齐官方 2.0+ 接口**，
1.0.5 下用普通 Redis 自行实现（高级查询降级为全量遍历 + 内存过滤）。
未来升级 2.0 + Redis Stack 时，**业务层零改动**。
另自行扩展了官方没有的**真分页** `pageByConversation`（官方只有 limit 无 offset）。

**⑦ 工具层零接线 + 数据源热插拔**

- 新增工具 = 新建一个 `@Component` + 一个 `@Tool` 方法，**无需改任何配置**；
- 新增天气数据源 = 新建一个 `WeatherProvider` 实现 + `@Order(n)`，**`weatherTool` 零改动**。

---

## 4. 项目结构

```
agentDemo1_0
├── pom.xml
├── README9_6.md                          ← 上一版文档
├── README9_7.md                          ← 本文档
├── FRONTEND-REQUIREMENTS-会话记忆优化.md   ← 二级记忆的前端对接需求
└── src
    ├── main
    │   ├── java/com/bbb/exercise/agentdemo1_0
    │   │   ├── AgentDemo10Application.java
    │   │   ├── agent
    │   │   │   └── AgentRunner.java               ★ 两阶段直播 + 手动工具循环（673 行）
    │   │   ├── config
    │   │   │   ├── AiConfiguration.java           ★ AI 装配中心（ChatModel/ChatClient/Advisor/提示词）
    │   │   │   ├── OpenAiProperties.java          ★ @ConfigurationProperties(spring.ai.openai)
    │   │   │   ├── ChatMemoryProperties.java      ★ @ConfigurationProperties(chat.memory)
    │   │   │   ├── ToolConfig.java                ★ @Tool 自动扫描注册
    │   │   │   ├── RedisConfig.java               会话记忆专用 ObjectMapper
    │   │   │   ├── TavilyConfig.java              Tavily WebClient
    │   │   │   └── WebConfig.java                 WebFlux CORS
    │   │   ├── controller
    │   │   │   └── ChatController.java            8 个 REST 端点
    │   │   ├── dto
    │   │   │   ├── ChatRequest.java
    │   │   │   ├── MessageWithConversation.java   ★ 带会话/元数据的消息 DTO
    │   │   │   └── PageResult.java                通用分页结果
    │   │   ├── enums
    │   │   │   └── ChatEventTypeEnum.java         ★ 10 类事件（1001~1010）
    │   │   ├── memory
    │   │   │   ├── AdvancedRedisChatMemoryRepository.java  ★ 对齐官方 2.0 的高级查询接口
    │   │   │   ├── RedisChatMemoryRepository.java          ★ 普通 Redis 实现（Sorted Set）
    │   │   │   └── MemoryCompressionService.java           ★ LLM 摘要压缩 + 后台调度
    │   │   ├── service
    │   │   │   ├── ChatService.java
    │   │   │   └── impl/ChatServiceImpl.java      流式管道编排 + 二级记忆查询
    │   │   ├── tools
    │   │   │   ├── TavilySearcher.java            Tavily /search 薄壳
    │   │   │   ├── attractionTool.java
    │   │   │   └── weather/
    │   │   │       ├── weatherTool.java           @Tool 门面（只做校验 + 委托）
    │   │   │       ├── WeatherService.java        ★ 多源责任链门面
    │   │   │       ├── WeatherProvider.java       数据源抽象
    │   │   │       ├── OpenMeteoWeatherProvider.java  ★ 首选（@Order(1)）
    │   │   │       └── TavilyWeatherProvider.java     兜底（@Order(2)）
    │   │   ├── utils（22 个）                     基于 Hutool 的自封装工具库
    │   │   │   ├── StringUtils（extends StrUtil，新增 toString/truncate）
    │   │   │   ├── AssertUtils / CollUtils / DateUtils / JsonUtils ...
    │   │   │   └── ConversationKeys               会话键解析
    │   │   └── vo
    │   │       └── ChatEventVO.java               SSE 单行事件
    │   └── resources
    │       ├── application.yml                    ★ 新增 redis / chat.memory 配置段
    │       └── system_prompt                      ★ 新增「思考：」行规范
    └── test/java/com/bbb/exercise/agentdemo1_0
        ├── AgentDemo10ApplicationTests.java       上下文加载
        ├── ChatServiceImplTest.java               服务契约（11 用例）
        ├── agent/AgentRunnerTest.java             ★ 直播协议（7 用例）
        └── config/
            ├── AiConfigurationTest.java           ★ 装配正确性（3 用例）
            └── ToolConfigTest.java                ★ 工具自动扫描（1 用例）
```

主源码约 **3,600 行 / 35 个类**，测试约 **590 行 / 5 个类**。

---

## 5. 核心模块详解

### 5.1 `config.AiConfiguration` —— AI 装配中心（原 `LlmConfig` 演进）

| Bean | 说明 |
|------|------|
| `ChatMemory chatMemory()` | `MessageWindowChatMemory`（`InMemoryChatMemoryRepository`，`maxMessages=50`），记忆顾问与 `AgentRunner` 共用同一实例 |
| `String systemPrompt(Resource)` | **集中读取** `classpath:system_prompt` 为字符串 Bean，启动期 fail-fast |
| `OpenAiChatModel qwenChatModel(OpenAiProperties)` | 底层模型：`OpenAiApi`（api-key / base-url / `/chat/completions`）+ 默认选项（model、可选 temperature） |
| `List<Advisor> agentAdvisorChain(ChatMemory)` | 可复用 Advisor 链：`SimpleLoggerAdvisor` + `MessageChatMemoryAdvisor` |
| `ChatClient qwenChatClient(...)` | 高层客户端：一次性配好 `defaultSystem` + `defaultAdvisors` + `defaultToolCallbacks`，同步路径开箱即用 |

亮点：装配与业务解耦，`AgentRunner` 只做**声明式注入**（`String systemPrompt`），
不再在构造器里做文件 IO、不再手写 `OpenAiApi`。

### 5.2 `config.ToolConfig` —— 工具自动发现

```java
for (String beanName : applicationContext.getBeanDefinitionNames()) {
    Class<?> type = context.getType(beanName, false);   // 类型级探测，不实例化
    if (type != null && hasToolMethod(type)) {          // 反射找 @Tool 方法
        toolObjects.add(applicationContext.getBean(beanName));
    }
}
return MethodToolCallbackProvider.builder().toolObjects(...).getToolCallbacks();
```

**新增工具零接线**——这正是「开闭原则」在 Agent 工具层的落地。

### 5.3 `agent.AgentRunner` —— 两阶段直播核心

| 成员 | 说明 |
|------|------|
| `chat(question, conversationId)` | 同步路径：走 `ChatClient`，工具由 Spring AI 内部执行 |
| `stream(question, conversationId)` | 直播路径：`SESSION_INFO → runTurn(0) → USAGE` |
| `runTurn(turn, roundIndex)` | **决策轮 + 递归工具循环**，`Flux.defer(...).subscribeOn(Schedulers.boundedElastic())` |
| `decisionOptions()` | `internalToolExecutionEnabled(false)` + 注入全部 `ToolCallback` |
| `executeTool(...)` | 执行单个工具，产出 STARTED / RESULT / FAILED 事件，异常文本回灌模型 |
| `finalAnswerFlow(...)` | 汇总 DATA → 双写记忆 → `chunkAnswer` 分块 + `delayElements(30ms)` |
| `loadConversationContext(turn)` | 显式复刻 `MessageChatMemoryAdvisor#before` 语义（system + 历史 + user） |
| `lazyBackfillFromRedis(...)` | 内存未命中时从 Redis 回填最近 `windowSize` 条 |
| `savePartialResponse(...)` | 中断时把已生成文本双写回记忆 |
| `clearHistory(...)` | 双清：内存窗口 + Redis（主键 / seq 键 / act 键） |
| `ReasoningSplitter`（内部类） | 字符级状态机，增量解析「思考：」前缀行 → REASONING |
| `isCompleteToolCall(...)` | 工具参数合法性校验（非空 + Jackson 可解析） |

关键常量：`MAX_TOOL_ROUNDS=5`、`RESULT_EVENT_MAX_LEN=500`、
`ANSWER_CHUNK_SIZE=12`、`ANSWER_REPLAY_INTERVAL=30ms`、`FIXED_FALLBACK`（兜底文案）。

### 5.4 `memory` 包 —— 二级记忆

**`RedisChatMemoryRepository`** 数据结构（普通 Redis，无需 Redis Stack）：

| Key | 类型 | 说明 |
|-----|------|------|
| `chat:memory:<conversationId>` | Sorted Set | member = `<seq>:<json>`；score = `epochMilli*1000 + seq%1000`（同毫秒按 seq 排序） |
| `chat:memory:seq:<conversationId>` | String | `INCR` 生成单调递增序号 |
| `chat:memory:act:<conversationId>` | String | 最后活跃时间戳，供压缩调度判断空闲 |

三类 key 均设 TTL（默认 7 天）。

能力分层：
- **原生高效**：`pageByConversation`（`ZRANGE` offset+count + `ZCARD`）、`findByTimeRange`（`ZRANGEBYSCORE`）；
- **降级实现**：`findByType` / `findByContent` / `findByMetadata` / `executeQuery` —— 全量遍历 + 内存过滤（管理类查询可用，热路径不应调用）。

**`MemoryCompressionService`**：
- 触发：后台守护线程每 60s 扫描 → 会话空闲 ≥10min → 历史 >10 条且「上条摘要后新积累」> keepRecent；
- 生成：精心设计 prompt（第三人称、保留关键事实与意图、丢寒暄与工具细节、严格 ≤200 字）+ 硬截断保底；
- 落库：摘要作为 `SystemMessage("以下是此前对话的摘要：\n"+summary)`，带 metadata 双写 Redis + 内存窗口。

### 5.5 `tools` 包 —— 工具与数据源

```
weatherTool.getWeather(city)
        │ 只做 AssertUtils 校验 + trim
        ▼
WeatherService.getWeather(city)                 ← @Order 升序责任链
        ├─ ① OpenMeteoWeatherProvider（@Order(1)）
        │      城市 → geocoding-api 解析经纬度 → forecast 取 current
        │      → WMO 天气码映射中文 + 风向八方位 → 结构化中文描述
        └─ ② TavilyWeatherProvider（@Order(2)）  ← 失败自动降级
                TavilySearcher.search(query)

attractionTool.getAttraction(city, weather)
        └─ TavilySearcher.search(...)           ← 优先 answer，缺失回退拼接 results[].content
```

工具入参刻意使用**扁平 `String` 参数**而非 DTO：Spring AI 会生成
`{"city":"北京"}` 这类扁平 Schema，模型输出 token 更少、参数更不易出错。

> 工程细节：Open-Meteo 请求必须以 `java.net.URI` 形式交给 `WebClient`
> （传 `String` 会被当作 URI 模板二次编码，中文城市名变乱码）。

### 5.6 `service.impl.ChatServiceImpl` —— 流式管道

```java
agentRunner.stream(question, conversationId)
  .doFirst(GENERATING.put(cid, TRUE))                 // 打生成标记
  .doOnComplete/doOnError(GENERATING.remove(cid))
  .doOnCancel(清除标记 + savePartialResponse)          // 中断回写，避免上下文断层
  .takeWhile(GENERATING.get(cid) == TRUE)             // stop() 移除标记 → 流被截断
  .doOnNext(累计 DATA 文本到 output)                    // 供中断时保存
  .concatWith(STOP_EVENT)                             // 末尾必定补 1002
  .onErrorResume(→ ERROR(1004) 事件)                   // 错误不击穿 SSE
```

另新增 `pageHistory` / `messagesByConversation` / `summarize` 三个二级记忆方法。

---

## 6. 关键实现原理

### 6.1 两阶段直播的事件时序

```
SESSION_INFO(1010)
  → [ REASONING(1007) ]*                   模型「思考：」行
  → [ TOOL_CALL_STARTED(1005)
      → TOOL_CALL_RESULT(1006) | TOOL_CALL_FAILED(1008) ]*    最多 5 轮
  → DATA(1001) * N                         最终答案分块回放（12 字 / 30ms）
  → USAGE(1009)                            prompt/completion/total tokens + durationMs
  → STOP(1002)                             服务层在末尾补发
```

### 6.2 两条调用路径的记忆一致性

- **同步路径**：`ChatClient` 的 `MessageChatMemoryAdvisor` 自动读写记忆；
- **流式路径**：绕过 Advisor 管线（需要手动工具循环），因此 `loadConversationContext`
  **显式复刻** `MessageChatMemoryAdvisor#before` 的行为（system + 历史 + user），
  并在 `finalAnswerFlow` 只写回 `UserMessage` + `AssistantMessage`（不含中间工具消息），
  与顾问语义**完全一致**，避免两条路径记忆分叉。

### 6.3 依赖装配流向

```
AiConfiguration ──┬─ ChatMemory ─────────────┬─→ MessageChatMemoryAdvisor → agentAdvisorChain → qwenChatClient
                  ├─ String systemPrompt ────┤                                                        │
                  ├─ OpenAiChatModel ────────┴────────────────────────────────────────────────────────┘
                  │                                       │
ToolConfig ───────┴─ List<ToolCallback> ──┬───────────────┤
                                          │               ▼
                                          └────────→ AgentRunner ──→ RedisChatMemoryRepository
                                                        ↑  ↑              ↑
                                          ChatServiceImpl  MemoryCompressionService
                                                        ↑
                                                  ChatController
```

### 6.4 线程模型

决策轮是**阻塞 HTTP 调用**（`ChatModel.call` + 工具同步执行），
整体通过 `subscribeOn(Schedulers.boundedElastic())` 挪到弹性线程池，
**不阻塞 WebFlux 的 EventLoop 线程**，保证并发下其他请求不被拖慢。

---

## 7. REST API 接口说明

基础路径：`/api/chat`

| 方法 | 路径 | 请求 | 响应 | 说明 |
|------|------|------|------|------|
| POST | `/api/chat` | `ChatRequest` | `text/event-stream` | 流式直播（10 类事件） |
| POST | `/api/chat/sync` | `ChatRequest` | `{"answer":"..."}` | 同步对话 |
| POST | `/api/chat/stop` | `?sessionId=` | `{"stopped":true,"sessionId":"..."}` | 中断生成（幂等） |
| GET | `/api/chat/history` | `?sessionId=`（可选） | `{"history":[...],"count":N}` | 一级内存窗口历史（「我:/助手:」） |
| DELETE | `/api/chat/history` | `?sessionId=`（可选） | `{"cleared":true}` | 双清（内存 + Redis） |
| **GET** | `/api/chat/{sessionId}/messages` | `?page=1&size=20` | `PageResult<MessageWithConversation>` | 🆕 历史分页查询（Redis） |
| **GET** | `/api/chat/{sessionId}/messages/all` | — | `List<MessageWithConversation>` | 🆕 全量消息（含摘要，上限 1000） |
| **POST** | `/api/chat/{sessionId}/summarize` | — | `{"summarized":bool,"summary":"..."}` | 🆕 手动触发压缩 |

**请求体：**

```json
{ "question": "北京今天天气怎么样？适合去哪里玩？", "sessionId": "mem-1" }
```

**会话键规则**（`ConversationKeys`）：空 → `chat-default`；非空 → `chat-<trim(sessionId)>`。

---

## 8. SSE 事件协议（直播事件）

| 值 | 事件 | eventData | 说明 |
|----|------|-----------|------|
| 1001 | `DATA` | String | 答案文本增量（分块回放） |
| 1002 | `STOP` | null | 流末尾必定补发（服务层） |
| 1003 | `PARAM` | — | 预留未使用 |
| 1004 | `ERROR` | String | 错误描述 |
| 1005 | `TOOL_CALL_STARTED` | `{toolCallId, toolName, arguments}` | 🆕 工具开始 |
| 1006 | `TOOL_CALL_RESULT` | `{toolName, result}` | 🆕 工具结果（截断 500 字展示，完整结果回灌模型） |
| 1007 | `REASONING` | String | 🆕 模型「思考：」行 |
| 1008 | `TOOL_CALL_FAILED` | `{toolName, error}` | 🆕 工具失败 |
| 1009 | `USAGE` | `{promptTokens, completionTokens, totalTokens, durationMs}` | 🆕 用量与耗时 |
| 1010 | `SESSION_INFO` | `{conversationId, timestamp}` | 🆕 流开始一次 |

**示例：**

```json
{"eventType":1010,"eventData":{"conversationId":"chat-mem-1","timestamp":1757300000000}}
{"eventType":1007,"eventData":"需要先查询北京的实时天气。"}
{"eventType":1005,"eventData":{"toolCallId":"call_1","toolName":"getWeather","arguments":"{\"city\":\"北京\"}"}}
{"eventType":1006,"eventData":{"toolName":"getWeather","result":"北京 当前天气：晴。气温 26.3℃…"}}
{"eventType":1001,"eventData":"北京今天晴，气温 26℃，"}
{"eventType":1001,"eventData":"适合去颐和园和什刹海。"}
{"eventType":1009,"eventData":{"promptTokens":812,"completionTokens":126,"totalTokens":938,"durationMs":3420}}
{"eventType":1002,"eventData":null}
```

---

## 9. 一次完整对话的时序

以 `POST /api/chat`（多轮工具调用）为例：

1. **Controller** 用 `ConversationKeys.resolve()` 得到 `conversationId`，转交 `ChatService`；
2. **ServiceImpl** 校验问题非空 → 打 `GENERATING` 标记 → 调 `agentRunner.stream()`；
3. **AgentRunner** 发 `SESSION_INFO` → 进入 `runTurn(0)`（弹性线程池）：
   - 装载上下文：system prompt + 内存历史（空则**惰性回填** Redis）+ 本次问题；
   - **决策轮**：非流式 `ChatModel.call`（`internalToolExecutionEnabled=false`）；
   - 文本经 `ReasoningSplitter` 拆分 → `REASONING` / `DATA` 事件；
   - **无 tool_calls** → `finalAnswerFlow`：双写记忆 → 答案按 12 字/30ms 回放 → 结束；
   - **有 tool_calls** → 逐个 `executeTool`：发 STARTED → 校验参数 → 执行 → 发 RESULT/FAILED
     → 结果（或错误文本）作为 `ToolResponseMessage` 追加上下文 → **递归 `runTurn(round+1)`**；
4. 正常结束发 `USAGE`（多轮 token 累加 + 总耗时）；
5. **ServiceImpl** 末尾 `concatWith(STOP)`，前端收到 1002 收尾；
6. **中断场景**：`POST /stop` 移除标记 → `takeWhile` 截断 → `doOnCancel` 把已生成文本双写回记忆。

---

## 10. 配置说明

`src/main/resources/application.yml`：

| 配置段 | 关键项 | 值 / 说明 |
|--------|--------|-----------|
| `server` | `port` | `18080`（前端 Vite 代理与 CORS 直连均指向它） |
| `spring.ai.openai` | `api-key` / `base-url` | DashScope 兼容端点；**生产请用环境变量注入，勿明文入库** |
| `spring.ai.openai.chat.options` | `model` | `qwen-turbo`（可换 plus/max）；`temperature` 可选 |
| `spring.data.redis` | `host/port/password` | `localhost:6379` |
| `spring.data.redis.lettuce.pool` | `enabled/max-active` | 连接池（**必须**配 `commons-pool2` 依赖） |
| `tavily` | `api-key/base-url/search-depth/max-results/include-answer` | 景点搜索 + 天气兜底 |
| `chat.memory` | `window-size` `50` | 一级内存窗口 |
| | `max-conversations` `1000` | （预留）内存驻留会话上限 |
| `chat.memory.redis` | `key-prefix` `chat:memory:`、`time-to-live` `7d`、`max-messages-per-conversation` `1000` | 二级存储 |
| `chat.memory.summary` | `keep-recent` `10`、`idle-minutes` `10`、`max-length` `200`、`scan-interval-seconds` `60`、`enabled` `true` | 摘要压缩 |
| `logging.level` | `org.springframework.ai: DEBUG`、memory 包 DEBUG | 观察多轮工具调用与记忆读写 |

**系统提示词**（`system_prompt`）新增「思考：」行规范：
要求模型判断需要调用工具时**先输出一行**以「思考：」开头的简短推理，
该行仅用于系统侧展示（转为 REASONING 事件），**不作为最终答案的一部分**，
并禁止输出 Thought/Action/Observation 等其它中间过程。

---

## 11. 测试

| 测试类 | 用例数 | 覆盖内容 |
|--------|--------|----------|
| `AgentDemo10ApplicationTests` | 1 | Spring 上下文能否正常加载 |
| `ChatServiceImplTest` | 11 | 空/空白问题→1004；空流→恰好一个 1002；**新直播事件原样透传**；同步空问题友好文案；历史标签与空白过滤；stop 幂等；clear 委托 + 会话键前缀；会话键规则 |
| `agent.AgentRunnerTest` | 7 | 🆕 **事件顺序**（SESSION→REASONING→TOOL_STARTED→TOOL_RESULT→DATA→USAGE）；工具失败→FAILED 且继续到最终答案；无工具直答时不发工具事件且记忆持久化；**非法参数自愈**（错误回灌模型）；非法参数仍补齐对应 ToolResponse；单轮多工具按顺序执行；`isCompleteToolCall` 各种畸形入参 |
| `config.AiConfigurationTest` | 3 | 🆕 systemPrompt Bean 含工具指引；ChatModel 默认选项由配置属性绑定；Bean 完整装配 |
| `config.ToolConfigTest` | 1 | 🆕 自动扫描注册全部 `@Tool` Bean |

合计 **23 个用例**。Agent 侧测试用 `mock(ChatModel.class)` 模拟多轮决策响应，
不依赖真实 LLM / 网络 / Redis（Redis 相关走可选路径）。

本地编译：`mvnw.cmd compile`（或 `mvnw.cmd test`）。

---

## 12. 对比 README9_6 的改进清单

### 12.1 框架与依赖

| 项 | README9_6（9/6） | README9_7（9/8） | 收益 |
|----|------------------|------------------|------|
| Spring AI | `1.0.0-M6`（里程碑版） | **`1.0.5` GA** | 稳定 API，去掉 `spring-ai-core` → 改用 `spring-ai-client-chat` + `spring-ai-openai` |
| Redis | 无 | **新增** `spring-boot-starter-data-redis` + `commons-pool2` | 支撑二级记忆 |
| Jackson | `jackson-databind` | **新增** `jackson-datatype-jsr310` | `Instant` 序列化（消息时间戳） |
| 工具库 | 无 | **新增** Hutool 5.8.32 + 自封装 `utils`（22 类） | 统一空值/断言/截断等横切逻辑 |

### 12.2 配置与装配

| 项 | 9/6 | 9/8 |
|----|-----|-----|
| LLM 配置 | `LlmConfig` 里散落 3 个 `@Value` | **`@ConfigurationProperties` 属性类**：`OpenAiProperties`（api-key/base-url/model/temperature）、`ChatMemoryProperties`（window-size/redis/summary） |
| 系统提示词 | `AgentRunner` 构造器读文件（双重构造器、构造期 IO） | **`AiConfiguration#systemPrompt` Bean**，启动期 fail-fast，业务类只声明式注入 `String` |
| Advisor | 在 `ChatClient` 构造时现场 `new` | **`agentAdvisorChain` 独立 Bean**，多客户端复用同一事实来源 |
| ChatClient 默认值 | 每次调用现传 system/tools | **`defaultSystem` + `defaultAdvisors` + `defaultToolCallbacks`** 一次性配好 |
| 底层模型 | 被 ChatClient 包住，外部拿不到 | **独立暴露 `OpenAiChatModel` Bean**，供 `AgentRunner` 决策轮直接调用 |

### 12.3 Agent 执行引擎（改动最大）

| 项 | 9/6 | 9/8 |
|----|-----|-----|
| 流式实现 | `ChatClient.stream()` 直连模型流式输出 | **两阶段**：非流式决策轮（保证 tool_call 参数完整）+ 答案分块回放（12 字/30ms） |
| 工具执行 | Spring AI 内部自动执行，过程不可见 | `internalToolExecutionEnabled(false)`，**手动工具循环**，每步发事件 |
| 事件类型 | 4 类（1001~1004） | **10 类（1001~1010）**：TOOL_CALL_STARTED / RESULT / FAILED、REASONING、USAGE、SESSION_INFO |
| 思考过程 | 无（提示词仅禁止输出） | **`ReasoningSplitter` 字符级状态机**解析「思考：」行 → REASONING 事件 |
| 死循环防护 | 无 | **`MAX_TOOL_ROUNDS = 5`** 硬终止 |
| 参数非法 | 无防护，异常直接冒泡 | **`isCompleteToolCall()`（Jackson 校验）+ 错误回灌模型自愈重试** |
| 工具异常 | 中断流 | **回灌错误文本，模型可生成兜底回答**；模型无输出时 `FIXED_FALLBACK` 兜底 |
| Token 统计 | `collectUsage` 覆盖式累计 | 多轮 `AtomicLong` **累加** + `USAGE` 事件回传前端（含 durationMs） |
| 线程模型 | 未显式处理 | 决策轮 `subscribeOn(Schedulers.boundedElastic())`，**不阻塞 EventLoop** |
| 结果展示 | 无 | 工具结果截断 500 字发前端（完整结果仍回灌模型） |

### 12.4 会话记忆（架构级升级）

| 项 | 9/6 | 9/8 |
|----|-----|-----|
| 结构 | 单级 `InMemoryChatMemory` | **二级**：`MessageWindowChatMemory`（50 条）+ Redis 全量（TTL 7 天） |
| 写入 | 只写内存 | **双写**：内存窗口 + `RedisChatMemoryRepository.appendMessages` |
| 重启 | 记忆全丢 | **惰性回填**：内存未命中时从 Redis 取最近 N 条回填 |
| 存储结构 | HashMap | **Sorted Set**（`seq:json` + `score=ms*1000+seq%1000` 保证同毫秒有序）+ seq 键 + act 键 |
| 查询能力 | 仅内存全量 | **分页 / 全量 / 时间范围 / 类型 / 内容 / 元数据 / 自定义查询** |
| 接口前瞻 | — | **`AdvancedRedisChatMemoryRepository` 对齐 Spring AI 2.0+ 官方签名**，另扩展官方没有的真分页 |
| 压缩 | 无 | **`MemoryCompressionService`**：空闲 10min + 历史>10 触发，LLM 生成 ≤200 字摘要，原文保留 + 摘要追加，`metadata.summary` 防重复 |
| 清空 | 只清内存 | **双清**：内存 + Redis 三类 key |
| 中断回写 | 只写内存 | **双写** |

### 12.5 工具层

| 项 | 9/6 | 9/8 |
|----|-----|-----|
| 注册方式 | 两个工具类硬编码注入 `AgentRunner` 构造器 | **`ToolConfig` 扫描 `@Tool` 自动注册**，新增工具零接线 |
| 天气数据源 | 仅 Tavily 搜索（2~5s、依赖搜索引擎摘要稳定性） | **责任链**：Open-Meteo（免费免 Key、结构化、~300ms）优先，Tavily 兜底 |
| 天气输出 | 搜索摘要长文本 | **结构化**：温度/体感/湿度/风速/风向 + WMO 天气码中文映射 |
| Tavily 调用 | 两个工具各自重复写请求/解析样板 | **抽出 `TavilySearcher` 薄壳**统一请求体、解析（answer 优先 → results 回退）、错误抛出 |
| 工具入参 | DTO record（`WeatherRequest` / `AttractionRequest`）+ `@JsonPropertyDescription` | **扁平 `String` 参数 + `@ToolParam`**，Schema 更扁平、模型输出 token 更少 |
| 参数校验 | 无 | `AssertUtils.isNotBlank` + 统一 trim |
| 扩展成本 | 改 `AgentRunner` 构造器 + 配置 | 新建一个 `@Component` + `@Tool` 方法即可 |

### 12.6 接口与契约

| 9/6 | 9/8 |
|-----|-----|
| `POST /api/chat`（4 类事件） | 同路径，**10 类直播事件** |
| `POST /api/chat/sync` | 保留 |
| `POST /api/chat/stop` | 保留（幂等） |
| `GET / DELETE /api/chat/history` | 保留（DELETE 升级为双清） |
| — | **🆕 `GET /{sessionId}/messages`**（分页） |
| — | **🆕 `GET /{sessionId}/messages/all`**（全量含摘要） |
| — | **🆕 `POST /{sessionId}/summarize`**（手动压缩） |
| 响应体 `Map<String,Object>` 拼装 | 新增 `PageResult<T>`、`MessageWithConversation` 强类型 DTO |

### 12.7 测试与工程化

- 测试类 **2 → 5**，用例数从「若干」增至 **23**；
- 新增 `AgentRunnerTest` 守护**直播事件协议**（顺序、自愈、多工具、记忆持久化），
  这是本次最复杂逻辑的安全网；
- 新增装配测试（`AiConfigurationTest`、`ToolConfigTest`）守护配置属性绑定与工具自动发现；
- 日志升级：新增 memory 包 DEBUG、工具注册清单日志、天气数据源降级日志、
  每次请求 `耗时/tokens/err` 结构化日志。

### 12.8 一句话总结

> **9/6 是「能跑通 Function Calling 的最小 Demo」；
> 9/8 是「可观测（直播事件）、可扩展（工具/数据源热插拔）、
> 可容错（多源降级 + 自愈 + 兜底）、可持久化（二级记忆 + 摘要压缩）、
> 可演进（对齐 Spring AI 2.0 接口）」的 Agent 骨架。**

---

## 13. 已知限制与后续 TODO

| # | 限制 | 说明 / 建议 |
|---|------|-------------|
| 1 | 高级查询为降级实现 | `findByType/Content/Metadata/executeQuery` 为**全量遍历 + 内存过滤**，O(总消息数)。生产建议升级 Redis Stack 获得原生 RediSearch |
| 2 | 压缩调度为进程内 | `ScheduledExecutorService` 重启丢任务；可靠性要求高时改用 Redis Stream 等持久化队列 |
| 3 | 答案为「回放式伪流式」 | 决策轮是非流式调用，首字延迟取决于模型完整决策耗时。若模型侧流式 tool_call 参数可靠，可切回真流式 |
| 4 | `max-conversations` 未生效 | 配置项已声明，但 `InMemoryChatMemoryRepository` 无 LRU 会话淘汰；如需限制需自行实现带 LRU 的 Repository |
| 5 | 密钥明文入库 | `application.yml` 中 api-key / redis password 为明文，应改为环境变量注入 |
| 6 | 兜底文案为固定文本 | `FIXED_FALLBACK` 处留有 `TODO`：模型无输出时应再调一次 LLM 生成兜底回答 |
| 7 | 无向量检索 / RAG | 当前为工具调用范式，未接入向量库 |
| 8 | 未启用 Actuator | pom 中已注释，联网拉取后可放开，配合 Spring AI 的 Micrometer Observation 观测 LLM 调用 |

---

## 14. 附：代码清单索引

| 类 / 接口 | 全限定名（`com.bbb.exercise.agentdemo1_0.`） | 类型 | 状态 |
|-----------|----------------------------------------------|------|------|
| `AgentDemo10Application` | （根包） | 启动类 | 沿用 |
| `AgentRunner` | `agent.AgentRunner` | `@Component` | **重写** |
| `AiConfiguration` | `config.AiConfiguration` | `@Configuration` | **重命名自 LlmConfig 并重写** |
| `OpenAiProperties` | `config.OpenAiProperties` | `@ConfigurationProperties` | 🆕 |
| `ChatMemoryProperties` | `config.ChatMemoryProperties` | `@ConfigurationProperties` | 🆕 |
| `ToolConfig` | `config.ToolConfig` | `@Configuration` | 🆕 |
| `RedisConfig` | `config.RedisConfig` | `@Configuration` | 🆕 |
| `TavilyConfig` | `config.TavilyConfig` | `@Configuration` | 沿用 |
| `WebConfig` | `config.WebConfig` | `@Configuration` | 沿用 |
| `ChatController` | `controller.ChatController` | `@RestController` | 扩展（+3 端点） |
| `ChatService` / `ChatServiceImpl` | `service` / `service.impl` | 接口 + `@Service` | 扩展（+3 方法） |
| `ChatRequest` | `dto.ChatRequest` | DTO | 沿用 |
| `MessageWithConversation` | `dto.MessageWithConversation` | DTO | 🆕 |
| `PageResult` | `dto.PageResult` | DTO | 🆕 |
| `ChatEventVO` | `vo.ChatEventVO` | VO | 沿用 |
| `ChatEventTypeEnum` | `enums.ChatEventTypeEnum` | 枚举 | **扩展**（4 → 10） |
| `AdvancedRedisChatMemoryRepository` | `memory.AdvancedRedisChatMemoryRepository` | 接口 | 🆕 |
| `RedisChatMemoryRepository` | `memory.RedisChatMemoryRepository` | `@Component` | 🆕 |
| `MemoryCompressionService` | `memory.MemoryCompressionService` | `@Component` | 🆕 |
| `weatherTool` | `tools.weather.weatherTool` | `@Component` | 改写（委托门面） |
| `WeatherService` | `tools.weather.WeatherService` | `@Service` | 🆕 |
| `WeatherProvider` | `tools.weather.WeatherProvider` | 接口 | 🆕 |
| `OpenMeteoWeatherProvider` | `tools.weather.OpenMeteoWeatherProvider` | `@Component @Order(1)` | 🆕 |
| `TavilyWeatherProvider` | `tools.weather.TavilyWeatherProvider` | `@Component @Order(2)` | 🆕 |
| `attractionTool` | `tools.attractionTool` | `@Component` | 改写 |
| `TavilySearcher` | `tools.TavilySearcher` | `@Component` | 🆕 |
| `ConversationKeys` | `utils.ConversationKeys` | 工具类 | 迁包 |
| `StringUtils` 等 22 个 | `utils.*` | 工具类 | 🆕（基于 Hutool 封装） |

---

*文档结束。如需接口联调示例或前端对接约定，参见同目录
`FRONTEND-REQUIREMENTS-会话记忆优化.md`。*
