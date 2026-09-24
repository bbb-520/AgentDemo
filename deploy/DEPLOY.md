# Alibaba Cloud Linux 3 部署说明

本项目是 Spring Boot 单体应用。静态页面位于 `src/main/resources/static`，会随 JAR 一起提供；当前仓库不需要单独运行 Node 前端。

## 1. 本地构建

在项目根目录执行：

```bash
mvn clean package -DskipTests
```

构建产物：

```text
target/agentDemo1_0-0.0.1-SNAPSHOT.jar
```

上传后重命名为 `/opt/bbb-agent/app.jar`。

## 2. 服务器目录和用户

```bash
sudo useradd --system --home-dir /opt/bbb-agent --shell /sbin/nologin bbb-agent
sudo mkdir -p /opt/bbb-agent
sudo chown -R bbb-agent:bbb-agent /opt/bbb-agent
```

如果用户或目录已存在，不需要重复创建。

## 3. 初始化 MySQL

使用当前版本的 `src/main/resources/schema.sql`，不要使用旧的数据库备份脚本：

```bash
mysql -u root -p < schema.sql
```

建议给应用创建独立账号，并将账号写入 `/etc/bbb-agent.env`。

## 4. 环境变量

将 `.env.example` 复制到服务器：

```bash
sudo cp deploy/.env.example /etc/bbb-agent.env
sudo vi /etc/bbb-agent.env
sudo chmod 600 /etc/bbb-agent.env
```

必须填写 MySQL 的实际密码。当前 Redis 无密码，保留 `REDIS_PASSWORD=` 即可。

当前 Bucket 配置为杭州地域的 `bobo-world-bbb`，Endpoint 为 `oss-cn-hangzhou.aliyuncs.com`，相册前缀为 `one and one/`。OSS 仍然使用阿里云 OSS SDK；如果更换为腾讯 COS、MinIO、R2 等服务，需要先修改代码适配层。

AccessKey 只放在 `/etc/bbb-agent.env`，不要写入仓库或提交到 Git。已经在聊天、截图或日志中出现的 AccessKey 应立即禁用并重新生成。

## 5. 安装 systemd 服务

```bash
sudo cp deploy/bbb-agent.service /etc/systemd/system/bbb-agent.service
sudo systemctl daemon-reload
sudo systemctl enable --now bbb-agent
sudo systemctl status bbb-agent
```

查看启动日志：

```bash
sudo journalctl -u bbb-agent -f
```

本机健康检查：

```bash
curl http://127.0.0.1:18080/health
```

预期结果：

```json
{"status":"ok"}
```

## 6. 安装 Nginx 站点

```bash
sudo cp deploy/nginx-bbb-agent.conf /etc/nginx/conf.d/bbb-agent.conf
sudo nginx -t
sudo systemctl enable --now nginx
sudo systemctl reload nginx
```

访问：

```text
http://47.114.108.160/
```

## 7. 当前服务器检查结果

当前服务器已经确认：Alibaba Cloud Linux 3、Java 21、Nginx 1.24、MySQL 8.4、Redis 返回 `PONG`。Nginx 正在监听 80，MySQL 正在监听 3306，Redis 只监听本机 6379。

## 8. 网络端口

公网只需要开放 80（以后使用 HTTPS 时再开放 443）。18080、3306、6379 应保持只监听本机或只允许内网访问。除了 Linux 防火墙，还要在阿里云安全组中放行 80/TCP。

## 9. 上线前检查

当前 `ApiKeyCrypto` 是兼容实现，实际会原样保存用户的 DashScope API Key。公网部署前应替换为真正的加密存储实现，并规划旧数据迁移。
