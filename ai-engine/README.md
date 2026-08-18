# LifeInbox AI Engine

V0.2 Task 2 提供结构化的 TEXT 摘要 API。Python 只负责调用 LLM 并返回摘要，不连接 MySQL；InboxItem 和摘要持久化仍由 Java 负责。

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

即使缺少 LLM 环境变量，健康检查仍然可用；只有调用摘要接口时才会返回配置错误。

## 生成摘要

```powershell
$body = @{
  title = "摘要测试"
  text = "这是一条需要生成摘要的 TEXT 内容。"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8000/summarize" `
  -ContentType "application/json" `
  -Body $body
```

成功响应为明确的 JSON：

```json
{
  "summary": "生成后的摘要"
}
```

`text` 去除首尾空白后不能为空，最大 20,000 个字符；模型返回的摘要最大 2,000 个字符。Prompt 集中在 `app/prompts.py`。

## 运行测试

```powershell
uv run pytest
```

自动化测试使用 Fake Service 和 `httpx.MockTransport`，不会请求真实 LLM 或消耗付费 Token。
