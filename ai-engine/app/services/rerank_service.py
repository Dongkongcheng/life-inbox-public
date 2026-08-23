import math
import logging
from typing import Any

from app.schemas.rerank import RerankCandidate, RerankRequest, RerankResponse
from app.services.rerank_client import RerankClient, RerankInvalidResponseError


LOGGER = logging.getLogger(__name__)


class RerankService:
    """只重排调用方给出的 Item-level Candidate，不访问 MySQL 或 Qdrant。"""

    def __init__(self, rerank_client: RerankClient) -> None:
        self._rerank_client = rerank_client

    def rerank(self, request: RerankRequest) -> RerankResponse:
        if not request.documents:
            # 空候选是正常检索结果，不读取配置也不产生 Provider 调用。
            return RerankResponse(results=[])

        provider_response = self._rerank_client.rerank(
            request.query,
            [document.text for document in request.documents],
            min(request.top_k, len(request.documents)),
        )
        results = self._validate_provider_response(provider_response, request)
        LOGGER.info(
            "Rerank 响应校验成功，Candidate=%d，Result=%d",
            len(request.documents),
            len(results),
        )
        return RerankResponse(results=results)

    def _validate_provider_response(
        self,
        response: Any,
        request: RerankRequest,
    ) -> list[RerankCandidate]:
        if not isinstance(response, dict) or not isinstance(response.get("results"), list):
            raise RerankInvalidResponseError("Rerank 返回结构不合法")
        if len(response["results"]) > min(request.top_k, len(request.documents)):
            raise RerankInvalidResponseError("Rerank 返回结果超过请求上限")

        candidates: list[RerankCandidate] = []
        seen_indexes: set[int] = set()
        for raw_result in response["results"]:
            if not isinstance(raw_result, dict):
                raise RerankInvalidResponseError("Rerank 返回结构不合法")

            index = raw_result.get("index")
            raw_score = raw_result.get("relevance_score")
            if (
                isinstance(index, bool)
                or not isinstance(index, int)
                or index < 0
                or index >= len(request.documents)
                or index in seen_indexes
                or isinstance(raw_score, bool)
                or not isinstance(raw_score, (int, float))
            ):
                raise RerankInvalidResponseError("Rerank 返回了非法候选")

            score = float(raw_score)
            if not math.isfinite(score):
                raise RerankInvalidResponseError("Rerank 返回了非法 Score")

            seen_indexes.add(index)
            candidates.append(
                RerankCandidate(
                    id=request.documents[index].id,
                    score=score,
                )
            )
        return candidates
