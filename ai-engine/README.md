# LifeInbox AI Engine

V0.2 Task 5 在现有 TEXT Analyze 之外增加 URL 正文提取。URL 抓取成功后仍进入同一个 Analyze Pipeline，并通过一次 LLM 调用返回 `summary`、`category`、`tags`、`keywords` 和 `entities`；Python 不连接 MySQL，InboxItem 和分析结果仍由 Java 持久化。

## 安装依赖

```powershell
uv sync
```

## 启动服务

```powershell
$env:LIFEINBOX_LLM_API_KEY="<your-api-key>"
$env:LIFEINBOX_LLM_MODEL="<your-model>"
$env:LIFEINBOX_LLM_BASE_URL="https://your-provider.example/v1"
$env:LIFEINBOX_LLM_TIMEOUT_SECONDS="20"

uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

LLM Client 调用可配置 Base URL 下的 `/chat/completions`，使用 Bearer API Key。不要把真实 Key 写入 `.env.example` 或提交到 Git。项目没有安装 `python-dotenv`，所以 `.env.example` 只是配置清单，不会被应用自动加载。

启动后访问 `http://localhost:8000/health`，应返回：

```json
{
  "status": "ok",
  "service": "life-inbox-ai"
}
```

即使缺少 LLM 环境变量，健康检查仍然可用；只有调用 Analyze 或兼容 Summary 接口时才会返回配置错误。

## 分析 TEXT

```powershell
$body = @{
  title = "分析测试"
  text = "Spring AI 是 Spring 生态面向 AI 应用开发的框架。"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8000/analyze" `
  -ContentType "application/json" `
  -Body $body
```

成功响应为明确的 JSON：

```json
{
  "summary": "这段内容介绍了 Spring AI 的基本定位。",
  "category": "技术学习",
  "tags": ["Spring AI", "AI开发"],
  "keywords": ["ChatModel", "EmbeddingModel"],
  "entities": [
    {"name": "Spring AI", "type": "TECHNOLOGY"}
  ]
}
```

允许的 Category 为：`技术学习`、`学习成长`、`工作`、`求职`、`生活`、`财务`、`想法`、`资讯`、`其他`。Tags 必须有 1～5 个，每个最长 64 个字符，不能是空字符串或大小写不同的重复标签。

Keywords 允许 0～8 个，每项最长 64 个字符。Python 会执行 NFKC 和空白清理，并按大小写不敏感规则去重。Entities 允许 0～10 个，每项包含最长 128 个字符的 `name`，以及固定类型：`PERSON`、`ORGANIZATION`、`LOCATION`、`TECHNOLOGY`、`PRODUCT`、`EVENT`、`OTHER`。相同名称和类型的实体只保留第一项。

`text` 去除首尾空白后不能为空，最大 20,000 个字符；摘要最大 2,000 个字符。Prompt 集中在 `app/prompts.py`。LLM 请求使用 OpenAI-compatible JSON Mode；针对千问的结构化抽取场景，请求会关闭思考模式以减少等待和 Token 消耗。Python 还会使用 Pydantic 严格验证所有字段，异常结果不会交给 Java。

旧 `POST /summarize` 暂时保留相同请求和 `{ "summary": "..." }` 响应，用于兼容已有调用方；它内部复用包含全部五个字段的 Analyze Service，不会维护第二套 Prompt 或再次调用 LLM。

## 分析 URL

`POST /analyze/url` 接收网页 URL 和可选标题：

```powershell
$body = @{
  url = "https://example.com/article"
  title = "可选的 InboxItem 标题"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8000/analyze/url" `
  -ContentType "application/json" `
  -Body $body
```

响应继续使用与 TEXT 完全相同的 AnalyzeResult。Python 使用普通 HTTP 请求和 Beautiful Soup 提取 `article`、`main` 或 `body` 中的正文，不执行 JavaScript；动态页面若没有可用的初始 HTML 正文，会返回 `URL_CONTENT_EMPTY`。

URL 抓取采用以下边界：

- URL 最长 1,000 个字符，只允许 `http`、`https`，拒绝含账号密码的地址；
- 每一跳都解析并检查全部 DNS 地址，只允许公网 IP；连接固定到已检查 IP，同时保留原始 Host 和 HTTPS SNI，避免 DNS 重绑定绕过；
- 关闭环境代理和自动重定向，最多手动处理 5 次重定向，每一跳重新执行安全检查；
- Connect Timeout 为 3 秒，Read Timeout 为 8 秒；
- 只接收 HTML，流式读取上限为 1 MiB；
- 提取后的正文最多 20,000 个字符，再交给现有 Analyze Service；提取失败时不会调用 LLM。

网页读取错误使用固定的 `{ "code": "...", "detail": "..." }` 结构：

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | `URL_INVALID` | URL 无效或 Scheme 不支持 |
| 403 | `URL_BLOCKED` | URL 被 SSRF 安全策略阻止 |
| 408 | `URL_FETCH_TIMEOUT` | 网页请求超时 |
| 424 | `URL_FETCH_FAILED` | 网页访问失败 |
| 415 | `URL_CONTENT_TYPE_UNSUPPORTED` | 不是受支持的 HTML |
| 413 | `URL_RESPONSE_TOO_LARGE` | 网页响应超过 1 MiB |
| 422 | `URL_CONTENT_EMPTY` | 无法提取有效正文 |

## 运行测试

```powershell
uv run pytest
```

自动化测试使用 Fake Service、假 DNS 和 `httpx.MockTransport`，不会请求真实网页或 LLM，也不会消耗付费 Token。
