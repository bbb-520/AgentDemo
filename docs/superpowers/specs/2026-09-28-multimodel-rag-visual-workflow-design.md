# 多模型视觉创作与长期偏好记忆设计

## 目标

在现有 Bobo 视觉创作产品上增加多模型选择、RAG 视觉偏好记忆、可暂停的图片理解/创作对话，以及变体与 Remix 能力；旅行助手保持现有定位，不新增旅行领域功能。

## 约束与范围

- 本阶段只在本地 `AgentDemo/agentDemo1_0` 和 `AgentWebDemo` 开发，不部署远程服务器。
- P0/P1 的公网安全、数据库加固和部署治理不在本阶段改动。
- 后端采用模块化单体，不引入新的独立微服务；模块边界使用接口隔离，为未来拆分保留可能性。
- 现有 `/api/chat`、图片上传、图片任务和 Bobo World API 保持兼容。
- 用户可以不登录时以匿名会话使用基础流程；服务端仍保留匿名归属，防止资产越权。

## 模块设计

### Model 模块

使用 `ModelProvider` 注册表统一管理 GPT、Gemini、Qwen、GLM 与可配置 HY。文本、视觉和图片生成模型分别选择。Provider 适配器通过统一接口暴露能力、模型名、超时和错误分类；OpenAI-compatible Provider 作为通用实现，厂商差异留在适配层。

用户模型配置保存 provider、能力类型、模型名、密钥引用和启用状态。密钥读取只能在服务端调用时发生，接口仅返回掩码信息。

### Conversation 模块

`POST /api/chat` 的 question 和 attachments 都改为可选：

- 纯文本：使用当前会话上下文回答或等待必要输入。
- 纯图片：创建视觉理解任务，产出 Scene Card 后进入 `WAITING_FOR_INSTRUCTION`，不自动生成图片。
- 图片+文字：由 Agent 判断是识别、问答、生成或二次创作。
- 生成图片后：生成结果可作为后续 Remix 的输入。

统一事件增加 `SCENE_CARD`、`WAITING_FOR_INSTRUCTION`、`GENERATION_CREATED`，原有 `DATA`、`IMAGE_JOB`、`STOP`、`ERROR` 保持可解析。

### Memory 模块

将用户显式确认、收藏、拒绝、修改过的 Scene Card 和生成偏好转换为记忆记录。结构化字段保存 provider、主题、颜色、材质、构图、风格和来源；文本向量保存到 Redis Stack 向量索引。每次生成前按用户、类型和相似度召回，并将结果作为可解释的 preference context 注入创作上下文。用户可以查看、删除或暂停记忆。

没有可用 embedding Provider 时使用结构化精确匹配作为降级路径，不阻塞主流程。

### Generation 与 World 模块

一次生成请求支持多个变体；每个变体拥有 parent generation、prompt snapshot、provider/model、状态和输出资产。用户可以选择变体、继续 Remix、收藏和发布。

Bobo World 增加 Remix 来源、点赞/收藏、主题标签和举报状态；公开响应使用 Public DTO，不返回内部 prompt、provider key 或任务内部字段。

## 数据模型

- `user_model_profile`
- `user_model_secret`
- `vision_memory`
- `generation_request`
- `generation_variant`
- `world_reaction`
- `world_report`

现有 `image_job` 保留，新增字段或映射层不改变已存在任务读取语义。

## 成功标准

- 模型选择不会泄露密钥，未配置模型时返回清晰可恢复错误。
- 图片可以单独上传并停在等待用户指令状态。
- 用户后续文本可以引用最近 Scene Card 或生成结果完成二次创作。
- 视觉偏好至少能被记录、召回、解释和删除。
- 同一次生成可以得到多个变体并继续 Remix。
- 旧前端事件和旧图片任务仍可正常解析。
- 后端单元测试、前端类型检查和构建均通过。
