# LifeInbox AI Engine

V0.2 Task 3 将 TEXT 摘要升级为统一 Analyze：一次 LLM 调用返回 `summary`、有限 `category` 和受限 `tags`。Python 不连接 MySQL；InboxItem 和分析结果仍由 Java 持久化。

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
  "tags": ["Spring AI", "AI开发"]
}
```

允许的 Category 为：`技术学习`、`学习成长`、`工作`、`求职`、`生活`、`财务`、`想法`、`资讯`、`其他`。Tags 必须有 1～5 个，每个最长 64 个字符，不能是空字符串或大小写不同的重复标签。

`text` 去除首尾空白后不能为空，最大 20,000 个字符；摘要最大 2,000 个字符。Prompt 集中在 `app/prompts.py`。LLM 请求使用 OpenAI-compatible JSON Mode，Python 还会使用 Pydantic 严格验证所有字段，异常结果不会交给 Java。

旧 `POST /summarize` 暂时保留相同请求和 `{ "summary": "..." }` 响应，用于兼容已有调用方；它内部复用 Analyze Service，不会维护第二套 Prompt 或再次调用 LLM。

## 运行测试

```powershell
uv run pytest
```

自动化测试使用 Fake Service 和 `httpx.MockTransport`，不会请求真实 LLM 或消耗付费 Token。
