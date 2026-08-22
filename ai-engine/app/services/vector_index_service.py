import hashlib

from app.schemas.embedding import EmbeddingRequest
from app.schemas.vector_index import (
    VectorDeleteResult,
    VectorIndexRequest,
    VectorIndexResult,
)
from app.services.embedding_service import EmbeddingService
from app.services.vector_store_service import VectorStoreService


class VectorIndexService:
    """协调 Searchable Content → Embedding → Qdrant，保持生成与存储职责分离。"""

    def __init__(
        self,
        embedding_service: EmbeddingService,
        vector_store_service: VectorStoreService,
    ) -> None:
        self._embedding_service = embedding_service
        self._vector_store_service = vector_store_service

    def index(self, request: VectorIndexRequest) -> VectorIndexResult:
        if not self._vector_store_service.is_enabled():
            # 默认关闭时不调用 Embedding Provider，因此不会产生费用或影响已有 AI 能力。
            return VectorIndexResult(
                inboxItemId=request.inbox_item_id,
                indexed=False,
            )

        embedding = self._embedding_service.embed(EmbeddingRequest(text=request.text))
        content_hash = hashlib.sha256(request.text.encode("utf-8")).hexdigest()
        collection = self._vector_store_service.upsert(
            request.inbox_item_id,
            embedding,
            content_hash,
        )
        return VectorIndexResult(
            inboxItemId=request.inbox_item_id,
            indexed=True,
            collection=collection,
            model=embedding.model,
            dimension=embedding.dimension,
            contentHash=content_hash,
        )

    def delete(self, inbox_item_id: int) -> VectorDeleteResult:
        if not self._vector_store_service.is_enabled():
            return VectorDeleteResult(inboxItemId=inbox_item_id, deleted=False)

        # Qdrant 按 Point ID 删除本身是幂等操作，不存在的 Point 也视为成功。
        self._vector_store_service.delete(inbox_item_id)
        return VectorDeleteResult(inboxItemId=inbox_item_id, deleted=True)
