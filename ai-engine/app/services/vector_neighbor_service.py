from collections.abc import Callable

from app.config import EmbeddingSettings
from app.schemas.vector_neighbor import VectorNeighborRequest, VectorNeighborResponse
from app.services.vector_store_service import (
    VectorStoreDisabledError,
    VectorStoreService,
)


class VectorNeighborService:
    """用现有 Source Point 发现有界邻居，不重新生成 Embedding 或判断业务 Relation。"""

    def __init__(
        self,
        vector_store_service: VectorStoreService,
        embedding_model_loader: Callable[[], str] = (
            EmbeddingSettings.model_from_environment
        ),
    ) -> None:
        self._vector_store_service = vector_store_service
        self._embedding_model_loader = embedding_model_loader

    def find_neighbors(
        self,
        request: VectorNeighborRequest,
    ) -> VectorNeighborResponse:
        if not self._vector_store_service.is_enabled():
            # 关闭时不读取 Provider 凭据，更不会为了邻居发现重新生成 Source Vector。
            raise VectorStoreDisabledError("Vector Store 未启用")

        return self._vector_store_service.find_neighbors(
            inbox_item_id=request.inbox_item_id,
            embedding_model=self._embedding_model_loader(),
            limit=request.limit,
        )
