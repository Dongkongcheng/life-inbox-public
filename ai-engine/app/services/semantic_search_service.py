from app.schemas.embedding import EmbeddingRequest
from app.schemas.semantic_search import SemanticSearchRequest, SemanticSearchResponse
from app.services.embedding_service import EmbeddingService
from app.services.vector_store_service import (
    VectorStoreDisabledError,
    VectorStoreService,
)


class SemanticSearchService:
    """复用文档索引的同一 Embedding 空间，只返回可由 Java 重新解析的候选。"""

    def __init__(
        self,
        embedding_service: EmbeddingService,
        vector_store_service: VectorStoreService,
    ) -> None:
        self._embedding_service = embedding_service
        self._vector_store_service = vector_store_service

    def search(self, request: SemanticSearchRequest) -> SemanticSearchResponse:
        if not self._vector_store_service.is_enabled():
            # 关闭时先失败，避免一次没有检索意义且可能产生费用的 Query Embedding。
            raise VectorStoreDisabledError("Semantic Search 未启用")

        embedding = self._embedding_service.embed(
            EmbeddingRequest(text=request.query)
        )
        return SemanticSearchResponse(
            results=self._vector_store_service.search(embedding, request.limit)
        )
