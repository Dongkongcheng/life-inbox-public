from collections.abc import Callable
from typing import Any

import httpx

from app.config import EmbeddingSettings


class EmbeddingServiceError(RuntimeError):
    """Embedding Provider 网络、鉴权、限流或上游状态异常。"""


class EmbeddingTimeoutError(EmbeddingServiceError):
    """Embedding Provider 在配置时间内没有返回。"""


class EmbeddingInvalidResponseError(EmbeddingServiceError):
    """Embedding Provider 返回的 JSON 或向量结构不可信。"""


class EmbeddingClient:
    """调用与现有 LLM 相同 Provider 的 OpenAI-compatible Embeddings API。"""

    def __init__(
        self,
        settings_loader: Callable[[], EmbeddingSettings] = (
            EmbeddingSettings.from_environment
        ),
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self._settings_loader = settings_loader
        self._transport = transport

    def generate_embedding(self, text: str) -> Any:
        settings = self._settings_loader()
        try:
            # 使用 JSON 序列化传递不可信文本；日志不记录原文、密钥或完整向量。
            with httpx.Client(
                timeout=httpx.Timeout(settings.timeout_seconds),
                transport=self._transport,
            ) as client:
                response = client.post(
                    f"{settings.base_url}/embeddings",
                    headers={
                        "Authorization": f"Bearer {settings.api_key}",
                        "Content-Type": "application/json",
                    },
                    json={"model": settings.model, "input": text},
                )
                response.raise_for_status()
        except httpx.TimeoutException as exception:
            raise EmbeddingTimeoutError("Embedding 请求超时") from exception
        except (httpx.RequestError, httpx.HTTPStatusError) as exception:
            # 不透传 Provider 响应正文，避免泄露上游细节或间接暴露凭据。
            raise EmbeddingServiceError("Embedding 服务暂不可用") from exception

        try:
            return response.json()
        except ValueError as exception:
            raise EmbeddingInvalidResponseError("Embedding 返回结构不合法") from exception
