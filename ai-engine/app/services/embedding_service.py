import math
from typing import Any

from app.schemas.embedding import EmbeddingRequest, EmbeddingResult
from app.services.embedding_client import (
    EmbeddingClient,
    EmbeddingInvalidResponseError,
)


class EmbeddingService:
    """把已准备文本交给 Embedding Provider，并在边界处严格验证返回向量。"""

    def __init__(self, embedding_client: EmbeddingClient) -> None:
        self._embedding_client = embedding_client

    def embed(self, request: EmbeddingRequest) -> EmbeddingResult:
        provider_response = self._embedding_client.generate_embedding(request.text)
        model, vector = self._validate_provider_response(provider_response)
        return EmbeddingResult(
            model=model,
            dimension=len(vector),
            embedding=vector,
        )

    def _validate_provider_response(self, response: Any) -> tuple[str, list[float]]:
        if not isinstance(response, dict):
            raise EmbeddingInvalidResponseError("Embedding 返回结构不合法")

        model = response.get("model")
        data = response.get("data")
        if not isinstance(model, str) or not model.strip():
            raise EmbeddingInvalidResponseError("Embedding 返回缺少模型名称")
        if not isinstance(data, list) or len(data) != 1 or not isinstance(data[0], dict):
            raise EmbeddingInvalidResponseError("Embedding 返回缺少唯一向量")

        raw_vector = data[0].get("embedding")
        if not isinstance(raw_vector, list) or not raw_vector:
            raise EmbeddingInvalidResponseError("Embedding 返回了空向量")

        vector: list[float] = []
        for raw_value in raw_vector:
            if isinstance(raw_value, bool) or not isinstance(raw_value, (int, float)):
                raise EmbeddingInvalidResponseError("Embedding 包含非法数值")
            value = float(raw_value)
            if not math.isfinite(value):
                raise EmbeddingInvalidResponseError("Embedding 包含非法数值")
            vector.append(value)

        return model.strip(), vector
