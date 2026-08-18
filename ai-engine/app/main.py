from typing import Literal

from fastapi import FastAPI
from pydantic import BaseModel


class HealthResponse(BaseModel):
    """Java 用这个固定结构确认 AI 服务已启动且协议可解析。"""

    status: Literal["ok"]
    service: Literal["life-inbox-ai"]


# 当前入口只负责启动独立 AI 服务；Python 不连接 MySQL，也不拥有 InboxItem 数据。
app = FastAPI(title="LifeInbox AI Engine")


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    """提供轻量健康检查，为 Java 与 Python 的第一条通信链路服务。"""

    return HealthResponse(status="ok", service="life-inbox-ai")
