# LifeInbox AI Engine

V0.2 AI Engine 为 TEXT、URL、FILE、IMAGE 提供统一 Analyze。网页、文档或截图文字提取成功后进入同一个 Analyze Pipeline，并通过一次 LLM 调用返回 `summary`、`category`、`tags`、`keywords` 和 `entities`；Python 不连接 MySQL，InboxItem、文件和分析结果仍由 Java 管理。

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

## 分析 FILE

`POST /analyze/file` 使用 multipart 接收文件内容和可选标题：

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8000/analyze/file" `
  -Form @{ file = Get-Item ".\notes.txt"; title = "学习笔记" }
```

响应继续使用与 TEXT/URL 完全相同的 AnalyzeResult。Java 读取自己管理的文件并发送内容，Python 不接收服务器本地路径；提取出的正文只在本次请求中使用，不持久化。

文档提取边界：

- 仅支持 `.txt`、`.md` 和 `.pdf`；
- TXT/Markdown 必须是 UTF-8，支持 UTF-8 BOM；Markdown 作为普通文本读取，不渲染或执行；
- PDF 使用 pypdf 提取真实文本层，最多 100 页，不执行 OCR；
- 文件最大 10 MiB，规范化正文最大 20,000 字符；超限明确失败，不静默截断；
- 空文件、加密/损坏 PDF、扫描版或其他无文本 PDF 均不会调用 LLM。

文档错误使用固定的 `{ "code": "...", "detail": "..." }` 结构：

| HTTP | code | 含义 |
| --- | --- | --- |
| 415 | `FILE_TYPE_UNSUPPORTED` | 文件类型或 MIME 不支持 |
| 413 | `FILE_TOO_LARGE` | 文件超过 10 MiB |
| 422 | `FILE_ENCODING_UNSUPPORTED` | 文本不是 UTF-8 |
| 422 | `FILE_CONTENT_EMPTY` | 文档为空 |
| 422 | `FILE_PDF_ENCRYPTED` | PDF 已加密 |
| 422 | `FILE_PDF_NO_TEXT` | PDF 没有可提取文本，可能需要 OCR |
| 413 | `FILE_DOCUMENT_TOO_LONG` | 页数或正文超过当前限制 |
| 422 | `FILE_EXTRACTION_FAILED` | 文档损坏或解析失败 |

## 分析 IMAGE

`POST /analyze/image` 使用 multipart 接收图片内容和可选标题：

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8000/analyze/image" `
  -Form @{ file = Get-Item ".\course-notice.png"; title = "课程通知" }
```

> Windows PowerShell 5.1 的 `Invoke-RestMethod` 没有 `-Form` 参数，可使用 PowerShell 7、浏览器产品界面或其他 multipart 客户端。

处理规则：

- 使用本地 RapidOCR + ONNX Runtime CPU 识别中英文截图，不调用云 OCR，不需要额外系统程序或 API Key；
- 仅支持 `.jpg`、`.jpeg`、`.png`、`.webp`，并由 Pillow 实际打开和验证内容，不能只依赖扩展名；
- 图片最大 10 MiB，宽高分别不能超过 10,000，总像素不能超过 20,000,000；Pillow 解压炸弹保护保持开启；
- OCR 前使用 EXIF Orientation 做简单方向归一化；
- OCR 文字执行 NFKC 和空白清理，最多 20,000 个字符，超限明确失败；
- 少于 4 个有效字母、数字或中文字符时视为无有效文字，不调用 LLM；
- OCR 原文只在当前请求中临时使用，不持久化；成功后复用现有 Analyze Service 和统一五字段结果。

图片错误使用固定的 `{ "code": "...", "detail": "..." }` 结构：

| HTTP | code | 含义 |
| --- | --- | --- |
| 415 | `IMAGE_TYPE_UNSUPPORTED` | 不是当前支持的 JPG/PNG/WEBP |
| 413 | `IMAGE_TOO_LARGE` | 图片超过 10 MiB |
| 413 | `IMAGE_DIMENSIONS_TOO_LARGE` | 宽高或总像素超过限制 |
| 422 | `IMAGE_INVALID` | 图片损坏、伪装或内容无效 |
| 422 | `IMAGE_OCR_FAILED` | 本地 OCR 执行失败 |
| 422 | `IMAGE_TEXT_EMPTY` | 未识别到足够的文字，不调用 LLM |
| 413 | `IMAGE_TEXT_TOO_LONG` | OCR 文字超过 Analyze 输入上限 |

当前只做 OCR-based IMAGE Analyze。普通照片内容理解、图片描述、Qwen-VL 等通用 Vision，以及扫描 PDF OCR 均未实现。

## 运行测试

```powershell
uv run pytest
```

自动化测试使用本地文档 fixture、Fake Service、假 DNS 和 `httpx.MockTransport`，不会请求真实网页或 LLM，也不会消耗付费 Token。
