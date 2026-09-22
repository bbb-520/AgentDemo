# 图片生成与 OSS 配置

本项目的图片链路使用阿里云 OSS 私有 Bucket。浏览器通过后端签发的 PostObject 策略直传，Java 服务只保存对象键和任务元数据。

## 环境变量

真实密钥不要写入 Git，也不要提交到 `application.yml`：

```text
ALIYUN_OSS_REGION=cn-beijing
ALIYUN_OSS_BUCKET=bbb-image-prod
ALIYUN_OSS_ENDPOINT=oss-cn-beijing.aliyuncs.com
ALIYUN_OSS_ACCESS_KEY_ID=<RAM 用户 AccessKeyId>
ALIYUN_OSS_ACCESS_KEY_SECRET=<RAM 用户 AccessKeySecret>

# 图片生成模型密钥，当前先保留占位符即可
DASHSCOPE_API_KEY=<DashScope API Key>

# 数据库完成建表、OSS 凭据已配置后开启后台 Worker
IMAGE_JOBS_ENABLED=true
```

当前代码中的空值占位符是有意保留的；不要把真实 AccessKey 或 DashScope Key 写进仓库。

## RAM 最小权限

给 RAM 用户/角色绑定仅限本 Bucket 的对象权限，不授予整个账号的管理权限。策略可按实际 RAM 控制台格式调整为：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["oss:PutObject", "oss:GetObject", "oss:DeleteObject"],
      "Resource": [
        "acs:oss:*:*:bbb-image-prod/source/*",
        "acs:oss:*:*:bbb-image-prod/output/*",
        "acs:oss:*:*:bbb-image-prod/thumbnail/*"
      ]
    }
  ]
}
```

服务端只从 `ALIYUN_OSS_ACCESS_KEY_ID` / `ALIYUN_OSS_ACCESS_KEY_SECRET` 读取凭据；浏览器拿到的是一次性/短期上传策略或签名读取 URL。

后端部署在阿里云 ECS/VPC 后，可以把 OSS Endpoint 切换为：

```text
oss-cn-beijing-internal.aliyuncs.com
```

## 数据库

首次部署或已有数据库升级时，执行：

```text
database/bbb_agent_demo.sql
```

其中包含 `image_asset` 和 `image_job` 两张表。

应用启动配置 `spring.sql.init.mode=never`，所以已有数据库升级时必须显式执行脚本或用迁移工具执行等价的两张表变更。未建表时请保持 `IMAGE_JOBS_ENABLED=false`，完成迁移并配置 OSS 后再改为 `true`。

## OSS 约束

- Bucket 保持私有，并开启阻止公共访问。
- CORS 允许 `http://localhost:5173` 和 `http://127.0.0.1:5173`。
- 生产环境只增加正式前端域名，不使用长期 `*`。
- `source/` 保存上传原图，`output/` 保存结果，`thumbnail/` 预留缩略图。
- 浏览器只拿短期签名 URL，数据库不保存永久公网图片地址。
- 建议为 `source/` 与 `output/` 配置生命周期规则（例如按业务保留期自动删除）；删除用户时同步删除对应对象和任务元数据。
- 你提供的 CORS 配置（`http://localhost:5173`、`http://127.0.0.1:5173`，允许 POST/GET/PUT/DELETE/HEAD，允许请求头 `*`）可用于本地开发；生产只加入正式前端域名。
- 上线前确认 Bucket 已开启“阻止公共访问”，不要用公开读 URL 代替签名 URL。

## 请求链路

1. 前端请求 `/api/image-assets/upload-policy`，服务端生成带用户隔离前缀的对象键。
2. 前端把原图直接 POST 到 OSS，不经过 Java 堆内存；随后调用 `/{assetId}/complete`，服务端用 `HeadObject` 校验大小和类型。
3. 对话请求 `/api/chat` 携带 `attachments: [{ assetId, ... }]`。明确的图片生成意图创建 `image_job` 并通过 SSE 返回 `1011`；普通图片问题则使用源对象的短期签名 URL发送给视觉模型。单独图片会先暂存，等待下一轮文字指令。
4. Worker 从队列取任务，把源图短期签名 URL交给 DashScope/Qwen Image，结果落到 `output/`，前端轮询任务并在当前对话和“关于 bbb / 作品”视图展示。
