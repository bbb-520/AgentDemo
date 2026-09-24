# Bobo 图片二次创作与公开分享应用实施大纲

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this outline task-by-task after the user reviews and approves it.

**Goal:** 将现有旅行智能体收敛为只处理图片二次创作与 Bobo’s World 公开分享的前后端应用。

**Architecture:** 保留现有 hash 路由、SSE、OSS 直传、图片任务和 Bobo’s World 领域；后端移除天气/搜索工具，前端移除演示模式和过程展示，把 API Key 与作品 CRUD 合并到用户页。

**Tech Stack:** 前端 React 18 + TypeScript + Vite；后端 Spring Boot WebFlux + Spring AI + MySQL + Redis + 阿里云 OSS/DashScope。

**Spec:** 本文件同时作为第一版设计审查稿；用户确认第 7 节问题后，再拆成正式实现计划。

## Global Constraints

- 注册只需要用户名和密码，API Key 延后在用户页配置。
- 一个请求最多一个源图片，一个生成任务最多一个结果图片。
- 生成结果图不包含用户提示词；用户提示词只在用户页的作品详情中浏览。
- 公开作品必须来自当前用户已成功生成的结果图，客户端不得提交 OSS object key。
- 前端不展示思考过程、工具调用、token 用量或其它过程性卡片。
- API Key、Cookie、OSS object key 和内部用户信息不得进入前端可见响应或日志。
- 设置页不显示后端连接状态；优化后不存在本地模式。

## Review Focus

- 用户 ID 是否采用公开 UUID：决定数据库迁移和认证响应字段。
- 是否完全删除 Tavily：决定 Key 表结构、配置文件和聊天客户端依赖。
- 是否强制每次创作上传图片：决定 ChatService 路由和 Composer 校验。
- 用户页采用全页还是面板：决定 hash 路由和 App 状态边界。
- 首次引导和结果发布气泡的关闭/记忆规则：决定 localStorage、遮罩和无障碍行为。

> 日期：2026-09-24  
> 状态：待需求评审，尚未进入代码实施  
> 覆盖仓库：  
> - 后端：`D:/Desktop/Desktop/projectPractice/AgentDemo/agentDemo1_0`  
> - 前端：`D:/Desktop/Desktop/projectPractice/AgentWebDemo`

## 1. 目标与范围

将现有“旅行智能体”前端和后端收敛为“图片二次创作 + Bobo’s World 公开分享”应用，完成以下闭环：

1. 用户可以只用用户名和密码注册、登录。
2. 用户在聊天页上传一张图片并输入创作要求。
3. 聊天区立即显示用户发送的图片；生成完成后只显示文本说明和一张生成图片。
4. 生成图片悬停时显示“加入 Bobo’s World”和“下载到本地”图标操作。
5. 用户确认后才能公开图片；确认气泡居中显示，背景虚化，点击气泡外区域关闭。
6. 用户页统一管理阿里云 API Key 和自己公开/私有的 Bobo’s World 作品及文案。
7. 设置页只保留后端连接状态、黑白背景切换和退出登录，不再提供演示模式、模型密钥和账号管理。
8. 首次进入聊天页时顺序展示两个引导气泡：产品能做什么、API Key 在哪里配置。
9. 后端删除天气、搜索和其它非图片工具，补齐用户唯一 ID 和图片应用所需接口。

## 2. 当前代码客观审查

### 2.1 已有能力，可复用

后端已经具备以下基础能力，不建议重复造轮子：

- `app_user`、`auth_session`、密码哈希和 HttpOnly 登录 Cookie。
- `user_api_key` 加密存储及 `/api/settings/keys` 接口。
- `image_asset`、`image_job`、OSS 直传策略、图片生成任务轮询和签名 URL。
- `bobo_world_item` 表，以及发布、公共列表、我的作品、编辑、删除 API。
- `bobo-world` 领域已包含幂等约束、乐观锁、匿名展示和删除后清理队列。

前端已经具备以下基础能力：

- hash 路由、登录/注册、会话本地保存、SSE 流读取。
- 图片选择、预览、OSS 上传和图片任务卡片。
- Bobo’s World 公共列表和我的作品编辑面板。
- 生成结果图下载和发布 API 调用。

### 2.2 与本次目标冲突或不完整的地方

前端：

- `StartPage.tsx` 仍把入口导航写成“首页”，点击页面任意位置还会进入离线演示流程。
- `useSettings.ts`、`App.tsx`、`SettingsSheet.tsx`、`Composer.tsx`、`mock.ts` 仍保留演示模式和“切到真实后端”逻辑。
- `MessageItem.tsx`、`Thinking.tsx`、`LiveBlocks.tsx` 仍渲染思考过程、工具调用、用量和收尾说明，不符合“只显示文本和图片”。
- 用户消息只保存图片元数据和文件名，聊天气泡没有显示用户实际图片。
- `ImageJobCard.tsx` 的操作按钮常驻显示，发布确认内容是卡片内弹层，还没有统一成图片悬停图标和页面中央气泡。
- `UserProfileSheet.tsx` 只管理 Bobo’s World 作品；API Key 仍在 `SettingsSheet.tsx`，且用户页是面板，不是统一用户页。
- `SearchSheet.tsx`、搜索接口映射、旅行建议和天气演示数据仍在代码树中。
- 首次进入聊天页的产品引导尚未拆成“产品功能”和“API Key 配置位置”两个顺序气泡。

后端：

- `app_user.id` 已经是唯一自增主键，内部可用于归属校验，但 `/api/auth/*` 没有返回稳定的公开用户 ID，接口契约也未明确 ID 用途。
- 注册接口本身只要求用户名和密码，但前端 `SetupGuide.tsx` 仍把 Qwen/Tavily Key 当成在线使用前的必经步骤。
- `UserChatClientFactory` 和 `ChatService` 仍依赖 Tavily、天气和景点工具；`application.yml` 仍启用 Tavily/MCP 旅行说明。
- 现有对话链路既支持文本聊天、视觉问答，也支持图片生成，需要收敛成图片创作主链路。
- 生成提示词和图片任务响应需要明确“一次请求只返回一张图片”，并增加禁止在成图中加入额外文案的约束。

## 3. 建议的目标架构

### 3.1 前端页面和状态

采用现有 hash 路由，页面拆为：

- `#start`：开始页，导航入口为“登录”“Bobo’s World”“关于 bbb”。
- `#chat`：图片创作聊天页，要求登录后使用生成能力。
- `#profile`：用户页，统一管理 API Key 和 Bobo’s World 作品。
- `#settings`：设置页，只保留背景主题和退出登录。
- `#world`：公共 Bobo’s World 图片列表。

全局状态只保留：登录用户、主题、当前会话和首次引导状态。删除 `demoMode` 以及本地/直连后端切换作为产品状态；后端错误通过聊天和页面提示反馈。

### 3.2 后端领域

保留并强化四个领域：

1. `auth`：注册、登录、注销、当前用户和公开用户 ID。
2. `image` / `zine`：图片上传、生成任务、单张结果、签名下载地址。
3. `bobo`：发布授权、公共列表、用户作品 CRUD、匿名和可见性。
4. `chat`：只承担图片创作上下文和结果事件，不再编排天气、搜索或旅行工具。

删除或下线天气、景点、Tavily 和旅行 MCP 配置，避免未使用代码继续被调用。

## 4. 分阶段实施计划

### 阶段 0：需求与接口冻结

**产出：** 本大纲评审结论、前后端接口契约、迁移方案。

- 确认下方“待确认问题”中的产品决策。
- 固化登录响应、用户 ID、API Key、图片任务、Bobo’s World CRUD 的 JSON 字段。
- 明确图片上传大小、支持格式、文案上限、主题持久化和首次引导的记忆范围。
- 明确旧用户数据和旧前端 localStorage 的兼容策略。

### 阶段 1：后端用户身份和 API Key 规则

**主要文件：**

- `src/main/resources/schema.sql`
- `src/main/java/.../auth/AuthService.java`
- `src/main/java/.../auth/AuthController.java`
- `src/main/java/.../auth/UserApiKeyService.java`
- `src/main/java/.../auth/UserApiKeyController.java`
- `src/main/java/.../identity/ChatIdentityResolver.java`

**工作内容：**

- 保留内部 `app_user.id`，按评审结论增加或映射稳定公开用户 ID；登录、注册、`/api/auth/me` 返回该 ID。
- 注册继续只校验用户名和密码，不因缺少 API Key 失败。
- 只保留阿里云 DashScope/Qwen Key；Tavily Key 从接口、状态结构、前端和数据库迁移中移除或兼容读取后停止使用。
- 保证 Key 永不返回明文、永不出现在作品接口和聊天消息中。
- API Key 缺失时，生成接口返回可识别的“请到用户页配置阿里云 API Key”错误。

### 阶段 2：后端收敛为图片应用

**主要文件：**

- `src/main/java/.../chat/ChatService.java`
- `src/main/java/.../chat/UserChatClientFactory.java`
- `src/main/java/.../zine/ZineGenerationService.java`
- `src/main/java/.../zine/ZinePromptCompiler.java`
- `src/main/java/.../zine/DashScopeImageGenerationClient.java`
- `src/main/java/.../image/ImageJobService.java`
- `src/main/java/.../image/ImageJobController.java`
- `src/main/resources/application.yml`
- `src/main/resources/system_prompt`

**工作内容：**

- 图片请求统一走“上传图片 + 创作要求 + 单张生成图”链路。
- 生成任务只保存一张结果图及其签名访问地址；前端不渲染多图画廊。
- 系统提示词要求生成结果不复述或绘制用户提示词；用户提示词保存为作品元数据，只在用户页详情展示。
- 删除天气、景点、Tavily、搜索和旅行 MCP 工具的注入与配置。
- 明确文本为空、图片缺失、Key 缺失、生成失败、结果过期时的错误码和用户提示。
- 保留后端 SSE 的文本回复和图片任务事件，但不再向前端发送需要展示的思考/工具过程。

### 阶段 3：前端登录、路由和设置页

**主要文件：**

- `src/App.tsx`
- `src/components/StartPage.tsx`
- `src/components/SettingsSheet.tsx`
- `src/components/SetupGuide.tsx`
- `src/hooks/useSettings.ts`
- `src/lib/storage.ts`
- `src/lib/auth.ts`
- `src/index.css`

**工作内容：**

- 开始页“首页”改成“登录”；未登录点击进入登录/注册，已登录点击进入用户页。
- 删除演示模式、离线入口、切换直连后端按钮、模拟回答和相关状态字段。
- 设置页只显示黑/白背景切换和退出登录，不显示后端连接状态。
- API Key 配置从设置页移到用户页；注册成功后不强制进入 Key 配置步骤。
- 统一登录后跳转到聊天页，并在缺 Key 时提供用户页入口提示。
- 新增首次进入聊天页的两步引导气泡：先说明“上传图片并二次创作/可公开分享”，再说明“用户页可配置阿里云 API Key”；完成或跳过后按用户/浏览器范围记忆。
- 主题选择写入 localStorage，并在根节点切换黑/白主题 class。

### 阶段 4：聊天消息和图片交互

**主要文件：**

- `src/components/ChatArea.tsx`
- `src/components/MessageItem.tsx`
- `src/components/Composer.tsx`
- `src/components/ImageJobCard.tsx`
- `src/components/image-job.css`
- `src/types.ts`
- `src/hooks/useConversations.ts`
- `src/lib/api.ts`

**工作内容：**

- 用户发送图片后，消息立即显示图片缩略图和可访问的替代文本；图片上传失败时保留错误状态并允许重试。
- 优先使用本地 `URL.createObjectURL` 让当前会话立即显示；如需刷新后仍显示，再增加后端签名预览接口。
- `MessageItem` 只渲染用户文本、用户图片、助手文本和助手生成图片；移除思考过程、工具卡片、摘要卡、token 用量和其它过程性展示。
- 结果卡只渲染一张生成图。图片上方/下方不加入额外产品文案，操作按钮只在鼠标悬停或键盘聚焦时出现在图片下方。
- 两个操作统一为图标按钮，并提供 `aria-label`：上传 Bobo’s World、下载到本地。
- 点击上传后打开全页面遮罩和中央确认气泡；遮罩区域使用 blur，点击气泡外区域关闭，右上角保留关闭图标。
- 发布成功后操作按钮变为“管理这张图片”，跳转用户页对应作品。

### 阶段 5：用户页与 Bobo’s World

**主要文件：**

- `src/components/UserProfileSheet.tsx`（建议重命名为 `UserProfilePage.tsx`）
- `src/components/BoboWorld.tsx`
- `src/lib/api.ts`
- `src/App.tsx`
- 后端 `bobo/BoboWorldController.java`、`bobo/BoboWorldService.java`

**工作内容：**

- 把用户页改为真正的 `#profile` 页面，统一包含：用户名/公开 ID、阿里云 API Key 状态与更新、我的作品列表。
- API Key 输入框只能提交新值或清空，不回显明文；缺 Key 时从聊天错误直接定位到该区块。
- 作品列表保留文案增删改、匿名切换、公开/私有切换、删除、预览和并发冲突刷新。
- 继续使用后端 `bobo_world_item` 作为公开清单，不通过扫描 OSS 目录推断公开作品。
- 公共世界只展示明确确认发布的生成结果图；处理空状态、分页、签名 URL 失效和图片加载失败。

### 阶段 6：删除遗留功能和清理代码

**主要删除或下线范围：**

- 前端：`SearchSheet.tsx`、`lib/mock.ts`、旅行建议、天气文案、搜索 API、演示模式字段及其 CSS。
- 前端：`Thinking.tsx`、`LiveBlocks.tsx`、`SummaryCard.tsx` 中只服务于过程展示的部分；如果历史恢复仍需系统摘要，默认隐藏而不是显示为产品消息。
- 后端：`tools/weather/*`、`AttractionTool`、`TavilySearcher`、Tavily 配置、天气/景点 MCP 描述和不再使用的测试。
- 文档：README 从“旅行智能体”改为图片二次创作应用，更新启动方式和接口表。

### 阶段 7：验收和回归

按以下顺序验收：

1. 新用户可注册、登录，未配置 Key 也能进入聊天页。
2. 上传一张图片后，用户消息立即显示该图片。
3. 生成任务最终只显示一张图片和必要文本。
4. 生成图悬停显示两个图标，下载和发布都可用。
5. 发布确认气泡的遮罩、虚化、外部点击关闭和关闭图标符合要求。
6. 用户页能更新 Key、编辑文案、切换匿名/可见性、删除作品。
7. 设置页不再出现演示模式、Tavily、账号密钥表单或连接状态，只保留主题和退出登录。
8. 首次聊天依次出现两个引导气泡，完成后不重复打扰。
9. 搜索、天气、景点、演示模式代码路径不会被前端或后端调用。
10. 任何响应、日志和错误提示都不泄露 API Key、Cookie、内部对象键或不应公开的用户信息。

## 5. 主要接口草案

以下接口优先复用现有路径，减少前后端同时改名的范围。

### 5.1 认证

`POST /api/auth/register`、`POST /api/auth/login`、`GET /api/auth/me` 返回：

```json
{
  "authenticated": true,
  "userId": "公开用户 ID",
  "username": "用户名"
}
```

`POST /api/auth/logout` 保持 HttpOnly Cookie 注销行为。

### 5.2 阿里云 API Key

保留 `/api/settings/keys` 作为后端兼容路径，但前端只在用户页调用：

- `GET`：只返回 `configured` 和掩码状态，不返回明文。
- `PUT`：提交新的阿里云 Key；空值表示保持原值或按明确的清空接口清除。
- `DELETE`：清除当前用户 Key。

如果评审决定继续使用 `qwenApiKey` 字段名，接口文档中应注明它实际代表 DashScope/Qwen Key，避免再次出现 Tavily 依赖。

### 5.3 图片生成

沿用现有图片上传策略、OSS complete、`POST /api/chat` 和 `GET /api/image-jobs/{jobId}`，但契约固定为：

- 一次请求最多一个源图片。
- 一个生成任务最多一个结果图片。
- 任务完成事件包含 `jobId`、`status=SUCCEEDED` 和可短期访问的 `imageUrl`。
- 缺少 API Key 返回明确的可导航错误码。

### 5.4 Bobo’s World

沿用现有：

- `POST /api/bobo/items`
- `GET /api/bobo/world`
- `GET /api/bobo/items/mine`
- `PATCH /api/bobo/items/{itemId}`
- `DELETE /api/bobo/items/{itemId}`

继续使用 `jobId` 发布，不允许客户端提交 OSS object key 或永久 URL；公开对象只允许是当前用户已成功生成的结果图。

## 6. 风险、依赖和回滚点

| 风险 | 影响 | 处理方式 |
|---|---|---|
| 现有用户表只有自增 ID，公开 ID 规则未定 | 登录响应和后续扩展接口会反复修改 | 先确认数字 ID 或 UUID；迁移脚本一次完成 |
| DashScope Key 字段仍叫 qwen，旧数据与新命名不一致 | 用户页文案和后端字段容易混淆 | 接口层统一新名称，数据库保留兼容读取并安排迁移 |
| OSS 签名 URL 过期 | 聊天或世界页面图片失效 | 增加按需刷新 URL；公开列表只返回短期签名地址 |
| 本地 object URL 不可跨刷新恢复 | 刷新后用户消息只剩文本 | 首版保证当前会话即时显示；若要求持久恢复，再补签名预览接口 |
| 删除天气/搜索后旧测试或配置仍引用它们 | 构建或启动失败 | 先更新依赖注入和配置，再删除类和测试，最后全量构建 |
| 生成模型仍把用户要求写入画面 | 违反“禁止其它文案” | 系统提示词、负面提示和验收样例同时约束；对用户明确要求的画面文字单独确认规则 |
| 多仓库同时修改，接口版本不同步 | 前后端联调失败 | 阶段 0 固化 JSON；每个阶段先完成后端契约再改前端调用 |

## 7. 待用户确认的问题

以下问题会直接影响数据结构或交互实现，请在评审时逐项确认。文档中的默认值是推荐方案，不代表已执行。

1. **公开用户 ID**：是否接受新增不可变 UUID 作为公开 `userId`，内部继续保留自增主键？推荐“是”，因为不会暴露用户数量和数据库序号。
2. **API Key 范围**：是否完全删除 Tavily，只保留一个阿里云 DashScope/Qwen Key？推荐“是”。
3. **聊天输入规则**：是否要求每次创作都必须附带图片，纯文本请求直接提示上传图片？推荐“是”，这样产品边界最清晰。
4. **用户页形态**：用户名点击后是否采用 `#profile` 全页跳转，而不是现有右侧/居中面板？推荐“全页”，便于同时管理 Key 和作品。
5. **首次引导记忆范围**：两个气泡是“每个账号只显示一次”，还是“每个浏览器显示一次”？推荐按登录用户 ID 记忆，未登录则按浏览器临时记忆。
6. **首次引导关闭方式**：是否采用“下一步 / 完成”按钮，并允许点击气泡外区域跳过？推荐保留按钮，同时允许外部点击关闭。
7. **图片修改范围**：用户页首版是否只做文案、匿名、公开/私有和删除，不支持替换已发布图片？推荐首版不做替换，避免引入第二条上传和 OSS 清理链路。
8. **图片内文字规则**：本次确认改为生成图片不包含用户提示词；用户提示词只在用户页作品详情中浏览。若用户要求画面内出现其它文字，仍按本条规则单独过滤，不把提示词自动绘制进图片。
9. **结果图操作显示**：是否接受“鼠标悬停 + 键盘 focus 时显示，触摸设备默认显示”的无障碍策略？推荐“是”。
10. **旧聊天历史**：历史消息中的天气/搜索文本是否保留在数据库但在前端隐藏，还是进行一次性数据清理？推荐保留历史、隐藏旧过程 UI，避免破坏会话数据。
11. **设置页的后端项**：已确认优化后不存在本地模式，因此设置页不添加连接状态或后端地址编辑项。

## 8. 实施顺序建议

建议顺序为：

1. 先确认第 7 节问题并冻结接口。
2. 先改后端身份、Key 规则和图片生成契约，确保前端有稳定接口。
3. 再改前端登录/路由/设置和用户页。
4. 再改聊天图片显示、单图结果、悬停操作和确认气泡。
5. 最后删除天气/搜索/演示遗留并做跨仓库验收。

这样可以把最容易返工的“用户 ID、Key 命名、聊天输入规则和用户页形态”放在代码修改之前确定。
