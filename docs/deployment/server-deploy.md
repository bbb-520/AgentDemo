# 服务器部署

## 推荐拓扑

生产环境至少准备：

- Linux 服务器，Docker Engine 24+、Docker Compose v2+
- JDK 21 仅用于构建；运行时使用镜像内的 Temurin JRE
- 独立或托管 MySQL、Redis、OSS
- Nacos 生产集群，Sentinel Dashboard 不直接暴露公网
- Nginx/云负载均衡只将流量转发到 `agent-gateway:18000`

## 部署步骤

```bash
git clone <repository-url> agent-demo
cd agent-demo
cp .env.example .env
vi .env

# 先启动基础设施
docker compose -f infra/docker-compose.yml up -d

# 构建应用镜像
mvn -DskipTests package
docker compose -f infra/docker-compose.yml -f docker-compose.app.yml build

# 启动应用
docker compose -f infra/docker-compose.yml -f docker-compose.app.yml up -d
docker compose -f infra/docker-compose.yml -f docker-compose.app.yml ps
```

## 安全要求

- 替换所有 `change-*` 默认值。
- Nacos、Sentinel、MySQL、Redis 只允许内网访问。
- OSS 和模型 API Key 使用 Secret 或服务器环境变量，不写入 Git。
- Gateway 前置 TLS、WAF、上传大小限制和访问日志脱敏。
- 为镜像打不可变版本标签，例如 `agent-demo:20260929-001`，不要只使用 `latest`。

## 版本兼容性告警

本项目按要求保留 Spring Boot 4.1.1。Spring Cloud Alibaba 2025.1.0.0 的官方适配表面向 Spring Boot 4.0.x，因此首次部署必须执行完整的 Maven 依赖解析、应用启动和 Gateway/Nacos/Sentinel 冒烟验证。若出现 Spring Framework 7 或 Jackson 3 兼容错误，不应直接上线；需要在内部制品库验证针对 Boot 4.1.x 的 SCA 版本后再替换 BOM。

## 升级与回滚

1. 备份 MySQL 和 Nacos 配置。
2. 构建新版本镜像并执行 Compose config 校验。
3. 先滚动更新非 Gateway 服务，再更新 Gateway。
4. 运行健康检查、登录、SSE、图片任务和 AgentRevise 冒烟测试。
5. 失败时恢复上一版本镜像标签，保留数据库迁移记录并按回滚文档操作。
