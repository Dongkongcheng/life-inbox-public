from typing import Literal

from fastapi import Depends, FastAPI, HTTPException, status
from pydantic import BaseModel

from app.config import LlmConfigurationError
from app.schemas.summary import SummaryRequest, SummaryResponse
from app.services.llm_client import (
    LlmClient,
    LlmInvalidResponseError,
    LlmServiceError,
    LlmTimeoutError,
)
from app.services.summary_service import SummaryService


class HealthResponse(BaseModel):
    """Java 用这个固定结构确认 AI 服务已启动且协议可解析。"""

    status: Literal["ok"]
    service: Literal["life-inbox-ai"]


# 当前入口只负责启动独立 AI 服务；Python 不连接 MySQL，也不拥有 InboxItem 数据。
app = FastAPI(title="LifeInbox AI Engine")
summary_service = SummaryService(LlmClient())


def get_summary_service() -> SummaryService:
    """作为 FastAPI 依赖提供服务，方便测试用 Fake LLM 替换真实上游。"""

    return summary_service


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    """提供轻量健康检查，为 Java 与 Python 的第一条通信链路服务。"""

    return HealthResponse(status="ok", service="life-inbox-ai")


@app.post("/summarize", response_model=SummaryResponse)
def summarize(
    request: SummaryRequest,
    service: SummaryService = Depends(get_summary_service),
) -> SummaryResponse:
    """显式处理一段 TEXT；Python 只生成结果，InboxItem 仍由 Java 保存。"""

    try:
        return service.summarize(request)
    except LlmConfigurationError as exception:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="LLM 配置不完整",
        ) from exception
    except LlmTimeoutError as exception:
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail="LLM 请求超时",
        ) from exception
    except LlmInvalidResponseError as exception:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="LLM 返回的摘要无效",
        ) from exception
    except LlmServiceError as exception:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="LLM 服务暂不可用",
        ) from exception
