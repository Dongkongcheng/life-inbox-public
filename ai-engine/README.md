# LifeInbox AI Engine

V0.2 Task 1 只建立独立的 FastAPI 服务和结构化健康检查，不连接 MySQL，也不调用任何大模型。

## 安装依赖

```powershell
uv sync
```

## 启动服务

```powershell
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

启动后访问 `http://localhost:8000/health`，应返回：

```json
{
  "status": "ok",
  "service": "life-inbox-ai"
}
```

## 运行测试

```powershell
uv run pytest
```
