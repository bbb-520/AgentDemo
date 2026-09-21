# 免费部署说明

## 后端（Render Web Service）

本目录已经包含 `Dockerfile` 和 `render.yaml`。在 Render 中从 Git 仓库创建 Web Service，使用 Docker 部署即可。

必须设置以下环境变量：

```text
CORS_ALLOWED_ORIGINS=https://你的-netlify-域名.netlify.app
QWEN_API_KEY=...
TAVILY_API_KEY=...
MYSQL_HOST=...
MYSQL_PORT=3306
MYSQL_DATABASE=...
MYSQL_USERNAME=...
MYSQL_PASSWORD=...
REDIS_HOST=...
REDIS_PORT=6379
REDIS_PASSWORD=...
API_KEYS_ENCRYPTION_KEY=生成一个至少 32 位的随机字符串
```

数据库 / Redis 请使用你自己的公网实例；主机、端口、用户名与密码统一在 Render 环境变量中填写，不要提交到 Git。应用侧默认值见 `src/main/resources/application.yml`。

如果免费方案是用 TCP 隧道把本机 MySQL/Redis 映射到公网，主机与端口由隧道客户端动态分配，请以客户端显示的实际值为准，只填在 Render 后台。

用户登录后可在网页中保存自己的 Qwen/Tavily 密钥。密钥使用 `API_KEYS_ENCRYPTION_KEY` 加密后写入 MySQL，Render 重建时必须保留同一个加密密钥，否则历史密钥无法解密。

Render 会把公网端口放到 `PORT` 环境变量，Dockerfile 已经自动读取它；健康检查地址是 `/health`。

## 前端（Netlify）

连接 `AgentWebDemo` 目录，构建命令为 `npm run build`，发布目录为 `dist`。设置：

```text
VITE_API_URL=https://你的-render-后端地址.onrender.com
```

设置后重新部署。不要把 Qwen、Tavily、数据库或 Redis 密钥放进前端环境变量。

## 注意

Render 免费 Web Service 空闲后会休眠，首次请求可能需要等待启动；Render 官方文档说明免费实例在连续 15 分钟无请求后会休眠。MySQL 和 Redis 需要使用外部可公网访问的实例，或改造应用为无持久化演示模式。
