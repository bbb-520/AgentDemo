# 图片二次生成接口

当前 APP 的主链路是“对话 + OSS 直传 + 后台任务”。旧的 multipart 接口仍保留，方便单次调试，但前端工作台使用下面的 OSS 任务链路。

## 对话图片链路（主入口）

```text
POST /api/image-assets/upload-policy
Content-Type: application/json
```

请求示例：

```json
{"fileName":"photo.jpg","contentType":"image/jpeg","fileSize":1827364}
```

响应包含 `assetId`、`uploadUrl` 和 `fields`。浏览器将这些字段与 `file` 一起以 `multipart/form-data` POST 到 `uploadUrl`，然后调用：

```text
POST /api/image-assets/{assetId}/complete
```

发送对话时，`POST /api/chat` 的 JSON 增加：

```json
{
  "question":"保留人物，把背景换成柔和的黄昏纸张",
  "sessionId":"<conversation-id>",
  "attachments":[{"assetId":"<asset-id>","fileName":"photo.jpg","mimeType":"image/jpeg","fileSize":1827364}]
}
```

明确包含“生成/重绘/海报/纸刊”等创作意图时，SSE 会先返回 `1010 SESSION_INFO`，随后返回 `1011 IMAGE_JOB`，再返回简短确认文本和 `1002 STOP`。普通图片问题会把 OSS 签名 URL 与文字一起交给视觉模型，返回普通 `1001 DATA` 流。

只发送图片，或先发送图片处理要求时，服务端会把输入暂存到会话记忆并返回等待提示；下一轮补齐文字/图片后才开始作业，不需要重复上传已收到的图片。

任务状态通过以下接口查询：

```text
GET /api/image-jobs/{jobId}
GET /api/image-jobs?conversationId={conversationId}
GET /api/image-jobs/archive?limit=24
```

结果图片地址是短期签名 URL，不应持久化为永久公网地址。

## 兼容调试入口

项目仍保留一个不依赖聊天会话的图片编辑入口：

启动服务后也可以直接打开 `http://localhost:18080/` 使用网页工作台。

```text
POST /api/zine/generate
Content-Type: multipart/form-data
```

字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `image` | 是 | JPG、PNG、WEBP、BMP、TIFF 或 GIF，默认不超过 20 MB |
| `mode` | 否 | `gathered`（实景拼贴，默认）或 `distillation`（影像蒸馏） |
| `language` | 否 | `en`、`zh` 或 `bilingual`，控制纸刊微文案语言 |
| `text` | 否 | 希望原样保留的短文案；不传则由模型根据画面生成 |
| `guidance` | 否 | 用户的创作要求，例如“保留人物与海岸线的关系” |

示例：

```bash
curl -X POST http://localhost:18080/api/zine/generate \
  -F "image=@./photo.jpg" \
  -F "mode=gathered" \
  -F "language=zh" \
  -F "guidance=保留人物与远方山脊的关系，色彩克制一些"
```

成功返回：

```json
{
  "mode": "gathered",
  "model": "qwen-image-3.0-pro",
  "imageUrl": "https://...",
  "rationale": "...",
  "providerRequestId": "..."
}
```

## 配置

```powershell
$env:DASHSCOPE_API_KEY = "sk-..."
.\mvnw.cmd spring-boot:run
```

也可以继续使用现有的 `QWEN_API_KEY`。可覆盖的配置包括：

- `ZINE_IMAGE_MODEL`：默认 `qwen-image-3.0-pro`
- `ZINE_IMAGE_BASE_URL` / `ZINE_IMAGE_ENDPOINT`：切换区域或兼容的图像服务
- `ZINE_IMAGE_SIZE`：默认 `1024*1707`，接近 3:5 竖版纸刊比例
- `ZINE_MAX_UPLOAD_BYTES`：默认 20 MB

该兼容入口会在请求内存中转为 data URL，适合调试；生产前端应使用上面的 OSS 直传任务链路，避免大图经过 Java 堆内存。旧接口返回的 `imageUrl` 是否长期有效取决于图像服务商。

## 为什么没有把图片直接塞进 MCP

当前 `/mcp` 适合宿主模型调用小型结构化工具，而图片编辑需要浏览器直传和后台任务。让 APP 直接调用 `/api/image-assets` + `/api/chat` 更稳定，也更容易做上传大小限制、鉴权、计费和结果存储。后续如果需要让外部 Agent 编排图片工作流，可以增加一个只接收 `assetId` 的 MCP 包装工具，由 APP 先完成安全上传。

## 授权提醒

本实现的提示词编译器依据 `gathered-scenes-zine-skill` 的公开规则实现，并保留其两条创作路径名称。该仓库 README 声明仅限个人、非商业使用；如果 APP 计划收费、订阅、SaaS、代做或公司/客户项目，需要先取得作者 Zeejay0 的明确书面许可，或改写为自有创作规范后再商业化。
