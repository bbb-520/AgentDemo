# 生产基线验收清单

## 构建

- [ ] JDK 21、Maven 3.9+ 可用。
- [ ] `mvn -DskipTests=false verify` 通过。
- [ ] `mvn dependency:tree` 中 Spring Boot 保持 4.1.1。
- [ ] 所有服务可生成可执行 Spring Boot jar。

## 基础设施

- [ ] Nacos、MySQL、Redis、Sentinel Dashboard 健康。
- [ ] Nacos 中导入 `AGENT_GROUP` 配置。
- [ ] 服务均注册到 Nacos，服务名和端口正确。
- [ ] Nacos 不可用时 optional 配置行为符合预期，健康检查能反映状态。

## 网关与治理

- [ ] `/api/auth/**`、`/api/chat/**`、`/api/images/**`、`/api/bobo-world/**` 路由正确。
- [ ] `X-Request-Id` 生成并向下游透传。
- [ ] Sentinel Dashboard 能看到 Gateway 和服务资源。
- [ ] 限流时返回统一错误码，不泄露堆栈。

## 业务回归

- [ ] 登录/会话正常。
- [ ] SSE 聊天仍返回 `text/event-stream`。
- [ ] 图片上传和图片任务状态正常。
- [ ] Worker 重启不会重复执行已完成任务。
- [ ] AgentRevise 状态流转拒绝非法跳转。

## 安全与交付

- [ ] 仓库无真实模型 Key、OSS Secret、数据库密码。
- [ ] 镜像使用非 root 用户和不可变版本标签。
- [ ] `/actuator` 未对公网暴露敏感端点。
- [ ] 已完成数据库备份、升级和回滚演练。
- [ ] 用户完成验收后，才允许创建 commit 和 push GitHub。
