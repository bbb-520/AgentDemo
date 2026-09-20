# AgentDemo 架构说明

## 分层边界

当前项目采用“模块化单体”作为演进起点，模块按业务能力划分，而不是按 Controller/Service/DAO 全局分层：

```text
chat              HTTP/SSE 入口与对话编排
conversation      会话领域模型、归属校验、MySQL 持久化
identity          当前请求的可信身份解析
tools             Agent/MCP 工具及外部服务适配器
config            Spring 装配与外部化配置
utils/vo/dto      跨模块的轻量基础类型
```

依赖方向保持为：`chat -> conversation/identity`，`tools -> config`，而业务模块不直接依赖 WebClient 的创建细节。

## 关键设计决策

1. `AppProperties`、`TavilyProperties` 集中承载可部署配置，避免业务类通过字段注入读取环境变量。
2. 外部 HTTP 客户端由 `ExternalClientConfig`/`TavilyConfig` 统一创建，并使用限定名注入，避免多个 WebClient Bean 引起歧义。
3. 会话公开 ID、Redis memory key 和数据库主键各自承担不同职责：公开 ID 只用于 API，memory key 绑定租户/用户，数据库主键只用于关联消息。
4. 天气数据源通过 `WeatherProvider` 扩展点降级，新增供应商不需要修改工具入口。
5. SSE 的公开事件契约固定为 `SESSION_INFO -> DATA* -> STOP`；异常转为 `ERROR -> STOP`，前端无需处理半开连接。

## 下一阶段演进建议

- 接入 Spring Security 后，让 `ChatIdentityResolver` 只消费认证主体，保留匿名身份作为本地 profile 的实现。
- 将天气/Tavily 的同步工具调用迁移到支持响应式返回值的工具接口，彻底移除 `.block()`。
- 增加 Actuator、请求关联 ID、模型调用耗时和工具降级指标。
- 当会话查询/历史列表增长后，再把 `conversation` 拆成端口（domain）与 MyBatis 适配器（infrastructure）；当前规模保持模块化单体更易维护。
