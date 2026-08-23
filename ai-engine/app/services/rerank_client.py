from collections.abc import Callable
import logging
import time
from typing import Any

import httpx

from app.config import RerankSettings


LOGGER = logging.getLogger(__name__)


class RerankServiceError(RuntimeError):
    """Rerank Provider 网络、鉴权、限流或上游状态异常。"""


class RerankTimeoutError(RerankServiceError):
    """Rerank Provider 在配置时间内没有返回。"""


class RerankInvalidResponseError(RerankServiceError):
    """Rerank Provider 返回的 JSON 或排名结构不可信。"""


class RerankClient:
    """调用 Cohere 风格的批量 Rerank API，不使用 Chat LLM 模拟精排。"""

    def __init__(
        self,
        settings_loader: Callable[[], RerankSettings] = (
            RerankSettings.from_environment
        ),
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self._settings_loader = settings_loader
        self._transport = transport

    def rerank(
        self,
        query: str,
        documents: list[str],
        top_n: int,
    ) -> Any:
        settings = self._settings_loader()
        started_at = time.perf_counter()
        try:
            with httpx.Client(
                timeout=httpx.Timeout(settings.timeout_seconds),
                transport=self._transport,
            ) as client:
                response = client.post(
                    f"{settings.base_url}/reranks",
                    headers={
                        "Authorization": f"Bearer {settings.api_key}",
                        "Content-Type": "application/json",
                    },
                    json={
                        "model": settings.model,
                        "query": query,
                        "documents": documents,
                        "top_n": top_n,
                    },
                )
                response.raise_for_status()
        except httpx.TimeoutException as exception:
            LOGGER.warning(
                "Rerank Provider 超时，Model=%s，Candidate=%d",
                settings.model,
                len(documents),
            )
            raise RerankTimeoutError("Rerank 请求超时") from exception
        except (httpx.RequestError, httpx.HTTPStatusError) as exception:
            http_status = (
                exception.response.status_code
                if isinstance(exception, httpx.HTTPStatusError)
                else "N/A"
            )
            LOGGER.warning(
                "Rerank Provider 不可用，Model=%s，Candidate=%d，HttpStatus=%s，Failure=%s",
                settings.model,
                len(documents),
                http_status,
                exception.__class__.__name__,
            )
            # HTTP 状态码足以区分鉴权、限流和上游异常，不记录正文或敏感请求数据。
            raise RerankServiceError("Rerank 服务暂不可用") from exception

        latency_ms = int((time.perf_counter() - started_at) * 1_000)
        LOGGER.info(
            "Rerank Provider HTTP 成功，Model=%s，Candidate=%d，LatencyMs=%d",
            settings.model,
            len(documents),
            latency_ms,
        )
        try:
            return response.json()
        except ValueError as exception:
            raise RerankInvalidResponseError("Rerank 返回结构不合法") from exception
