# agentDemo1_0 项目长期记忆

## 技术基线
- Java 21 / Spring Boot 3.4.12 / Spring WebFlux / Spring AI **1.0.5**
- 唯一 Web 栈 WebFlux（不能加 spring-boot-starter-web MVC，否则 SSE 退化）
- 模型：阿里百炼 DashScope（OpenAI 兼容），qwen-turbo/plus/max
- Redis：本地 8.0.2 standalone 6379，密码 123456（Lettuce TCP 直连）

## 编译方式（本机 mvn boot/plexus-classworlds-2.8.0.jar 是损坏版本）
- 项目真实路径：**D:\Desktop\Desktop\projectPractice\...\agentDemo1_0**（双 Desktop）
- **坑**：本机 `/d/java/apache-maven-3.9.9-bin/apache-maven-3.9.9/boot/plexus-classworlds-2.8.0.jar`
  缺 `org.codehaus.plexus.classworlds.launcher.Launcher` 类，`mvn -v` 直接挂
- **修复**（已做）：从 `/c/Users/bbb/.m2/wrapper/dists/apache-maven-3.9.16-bin/.../apache-maven-3.9.16/boot/plexus-classworlds-2.11.0.jar`
  复制到 mvn boot 目录，并把坏的 `.jar` 改后缀（`mv ...2.8.0.jar{,.broken.bak}` 让通配符找不到）
- **绕行编译命令**（Windows / git-bash）：
```
M2="D:\\java\\apache-maven-3.9.9-bin\\apache-maven-3.9.9"
CW="D:\\java\\apache-maven-3.9.9-bin\\apache-maven-3.9.9\\boot\\plexus-classworlds-2.11.0.jar"
REPO="C:\\Users\\bbb\\.m2\\repository"
PROJ="D:\\Desktop\\Desktop\\projectPractice\\AgentDemo\\agentDemo1_0"
/d/jdk21/bin/java -Dmaven.home="$M2" -Dclassworlds.conf="$M2\\bin\\m2.conf" \
  -Dlibrary.jansi.path="$M2\\lib\\jansi-native" -Dmaven.repo.local="$REPO" \
  -Dmaven.multiModuleProjectDirectory="$PROJ" -cp "$CW" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o -B compile -DskipTests
```
- java：`/d/jdk21/bin/java`（21.0.9），JAVA_HOME=`D:\jdk21`
- IDEA 必先 **右键 pom.xml → Add as Maven Project**（或 Reload All Maven Projects）才能识别
- 更彻底方案：`mvn wrapper:wrapper` 让项目自带 `mvnw.cmd`（待做）

## 依赖坑
- `spring-boot-starter-data-redis` **不传递 commons-pool2**：开 lettuce.pool.enabled=true 必须显式加 `org.apache.commons:commons-pool2`

## 会话记忆架构（2026-09-08 重构为 Redis 8 时代）
**新方案**：一级 LRU 内存窗口 + 二级 RedisJSON 文档 + RediSearch 索引 + Redis Streams 压缩任务

- **一级**：`LruInMemoryChatMemoryRepository`（替换 Spring AI 无界 InMemory），基于 `LinkedHashMap accessOrder=true + removeEldestEntry`，容量 = `chat.memory.max-conversations`（**真正生效**）
- **二级**：每条消息 1 个 RedisJSON key `chat:memory:<cid>:<seq>`，seq 由 `INCR chat:memory:seq:<cid>` 取号，TTL = `chat.memory.redis.time-to-live`
- **索引**：`chat-memory-idx ON JSON PREFIX 1 "chat:memory:"`，schema 含 conversationId/messageType/content/seq/timestamp/summary；启动期由 `RedisIndexInitializer @PostConstruct` 建索引
- **高级查询全部 FT.SEARCH**（消灭 KEYS + 全量扫）：findByType/Content/Metadata/TimeRange/Page；findConversationIds 改 `SCAN MATCH ... TYPE json`
- **压缩任务改 Streams**：`XADD chat:memory:compress-tasks * conversationId <cid>` + 消费者 `XREADGROUP `>`` + `XACK`；`chat.memory.redis.streams.{enabled,key,group,consumer-name,poll-interval-ms,batch-size}` 配置；重启不丢任务
- **旧数据**：启动期 `DataMigrator @PostConstruct` SCAN + DEL 旧 Sorted Set（开关 `cleanup-legacy-on-startup`，默认 true），**不做兼容迁移**
- **抽象**：业务层只见 `AdvancedRedisChatMemoryRepository` 接口，签名保持与改造前完全一致

## P0/P1/P2 修复（9/8 全部闭环 7/8）
- P0-1 摘要污染 default 会话 → MemoryCompressionService 注 `ChatModel`（裸模型绕开 Advisor）
- P0-2 同步接口不入 Redis → AgentRunner.chat() 末尾 appendMessages
- P1-3 KEYS → SCAN
- P1-4 max-conversations 未生效 → 自建 LRU
- P1-5 window-size 硬编码 → AiConfiguration 注 ChatMemoryProperties
- **P1-6 双写非原子 未闭环**（列入路线图，下次用 Redis Lua 脚本）
- P2-7 /history 无惰性回填 → AgentRunner.history() 加惰性回填
- P2-8 压缩调度丢任务 → Streams

## 关键文档
- **bbb.md**：当前主文档（2026-09-08 重写，「Redis 8 改造总结 + 待优化路线图」8 章）
- README9_7.md：项目功能 / API / SSE 协议（仍作主参考）
  README9_6.md：9/6 旧版，仅作历史对照
- HELP-会话记忆.md：会话记忆机制说明（**内容已过时**，新机制见 bbb.md §3）
- bbb.md 上版（已删除）：原标题「二级会话记忆实现详解 & Redis Stack 升级指南」

## 架构要点（2026-09-08 源码核对）
- AgentRunner = **两阶段直播**：非流式决策轮(internalToolExecutionEnabled(false)) + 手动工具循环(MAX_TOOL_ROUNDS=5) + 答案回放(12字/30ms, boundedElastic)；SSE 事件 1001~1010 共 10 类
- ReasoningSplitter 解析「思考：」行 → REASONING；非法 arguments 用 Jackson 校验后把错误回灌模型自愈
- 工具自动注册：config/ToolConfig 扫描 @Tool 方法 → List<ToolCallback>，新增工具零接线
- 天气责任链：WeatherProvider{OpenMeteo @Order(1) 首选, Tavily @Order(2) 兜底} + WeatherService 门面降级
- AiConfiguration 取代旧 LlmConfig；systemPrompt / agentAdvisorChain 均为独立 Bean

## Gitee 仓库
- 远端：https://gitee.com/bee-bop-bop/agentDemo1_0.git
- 本地 master 分支：f82a317 Initial commit + d6f4b5d Redis 8 改造（52 源文件 +3768 行）
- push 需要 token：用户自行在 https://gitee.com/profile/personal_access_tokens 生成

## git 工作流注意
- working tree 大量 utils/*.java 标记为 modified 但 diff 经常是空白字符（CRLF/LF）差异，不要被吓到
- .gitignore 排除了 target/、.idea/、.workbuddy/memory/*.md（保留 MEMORY.md 入仓）
- ⚠️ application.yml 仍硬编码 api-key 和 tavily-key，**未做 ENV 注入**（下次路线图 §7.6）

## 待优化路线（摘自 bbb.md §7）
1. [高] P1-6 双写非原子 → Redis Lua + outbox
2. [中] Spring Boot 3.5.x + Spring AI 1.1.x 小版本升级
3. [中] `LANGUAGE chinese` 中文分词 + content_tag TAG 副本
4. [中] 压缩改 parallelStream + 限流
5. [中] application.yml api-key / password 改 ${ENV}
6. [低] Testcontainers + MockMvc 集成测试
7. [低] spring-ai-commons-vector-store（RAG 远期）
