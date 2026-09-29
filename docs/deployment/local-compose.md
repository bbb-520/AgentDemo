# 本地基础设施

当前环境需要安装 Docker Desktop、JDK 21 和 Maven 3.9+。

```powershell
Copy-Item .env.example .env
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml ps
scripts\import-nacos.ps1
```

默认地址：

- Nacos: http://localhost:8848/nacos
- Sentinel Dashboard: http://localhost:8080
- MySQL: localhost:3306
- Redis: localhost:6379

首次启动后，应将 `infra/nacos/import` 下的配置导入 Nacos 的 `AGENT_GROUP`。生产环境必须替换 `.env` 中所有默认密码和 Token。
