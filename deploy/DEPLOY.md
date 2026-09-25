# AgentDemo + AgentWebDemo 部署说明

AgentDemo 是 Spring Boot WebFlux 后端，AgentWebDemo 是 React/Vite 前端。生产环境由 Nginx 提供前端静态文件，并把 `/api/` 代理到本机的 Spring Boot 服务。

## 1. 构建

在 AgentDemo/agentDemo1_0 中执行：

```bash
mvn clean test package
```

产物为：

```text
target/agentDemo1_0-0.0.1-SNAPSHOT.jar
```

在 AgentWebDemo 中执行：

```bash
npm ci
npm run typecheck
npm run build
```

将 `dist/` 内容部署到服务器的 `/var/www/AgentWebDemo/`。

## 2. 初始化 MySQL

使用当前版本的 `src/main/resources/schema.sql`：

```bash
mysql -u root -p < schema.sql
```

脚本会创建 `bobo_db` 及其业务表。生产环境使用独立的 `bobo_user` 账号，并把实际密码写入 `/etc/bbb-agent.env`。

## 3. 环境变量

```bash
cp deploy/.env.example /etc/bbb-agent.env
chmod 600 /etc/bbb-agent.env
```

至少填写：

- `MYSQL_DATABASE=bobo_db`
- `MYSQL_USERNAME=bobo_user`
- `MYSQL_PASSWORD`
- `REDIS_HOST=127.0.0.1`
- `ALIYUN_OSS_ACCESS_KEY_ID`
- `ALIYUN_OSS_ACCESS_KEY_SECRET`

DashScope API Key 由用户在前端登录后保存，不要提交到仓库。OSS、Redis 和模型配置也只能放在服务器环境文件或受保护的密钥管理系统中。

## 4. 安装后端服务

```bash
install -d -o bbb-agent -g bbb-agent /opt/bbb-agent
install -o bbb-agent -g bbb-agent target/agentDemo1_0-0.0.1-SNAPSHOT.jar /opt/bbb-agent/app.jar
cp deploy/bbb-agent.service /etc/systemd/system/bbb-agent.service
systemctl daemon-reload
systemctl enable --now bbb-agent
```

检查：

```bash
curl http://127.0.0.1:18080/health
journalctl -u bbb-agent -n 100 --no-pager
```

预期健康响应：

```json
{"status":"ok"}
```

## 5. 安装 Nginx

```bash
rsync -a --delete AgentWebDemo/dist/ /var/www/AgentWebDemo/
cp deploy/nginx-bbb-agent.conf /etc/nginx/conf.d/bbb-agent.conf
nginx -t
systemctl enable --now nginx
systemctl reload nginx
```

Nginx 配置必须保留 `/api/` 前缀转发到 `127.0.0.1:18080`，并关闭代理缓冲以支持 `/api/chat` 的 SSE 流。不要添加 `/me` 代理；前端使用的是 `/api/auth/me`。

## 6. 网络规则

公网只开放 TCP 80；启用 HTTPS 后再开放 443。SSH 22 只允许管理 IP。3306、6379 和 18080 不对公网开放，Redis 单机部署绑定到回环地址。

## 7. 上线验证

```bash
curl -I http://127.0.0.1/
curl -i http://127.0.0.1/api/auth/me
curl -i http://127.0.0.1/health
ss -lntp | grep -E ':(80|443|18080|3306|6379)\\b'
```

浏览器登录后验证 `POST /api/chat` 是否持续收到 `text/event-stream` 数据。
