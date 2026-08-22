import hashlib
import re
from collections.abc import Callable
from datetime import UTC, datetime
from threading import Lock
from typing import Any

import httpx
from qdrant_client import QdrantClient, models

from app.config import VectorStoreSettings
from app.schemas.embedding import EmbeddingResult


class VectorStoreError(RuntimeError):
    """Qdrant 连接、鉴权、创建或写入失败的受控基类。"""


class VectorStoreTimeoutError(VectorStoreError):
    """Qdrant 没有在配置时间内响应。"""


class VectorStoreCompatibilityError(VectorStoreError):
    """已有 Collection 与当前向量空间不兼容。"""


class VectorStoreInvalidResponseError(VectorStoreError):
    """Qdrant 返回了无法安全解释的结构或状态。"""


class VectorStoreService:
    """只负责将已生成的向量保存到 Qdrant，不负责生成 Embedding 或业务查询。"""

    def __init__(
        self,
        settings_loader: Callable[[], VectorStoreSettings] = (
            VectorStoreSettings.from_environment
        ),
        client_factory: Callable[[VectorStoreSettings], Any] | None = None,
    ) -> None:
        self._settings_loader = settings_loader
        self._client_factory = client_factory or self._create_client
        self._clients: dict[VectorStoreSettings, Any] = {}
        self._clients_lock = Lock()

    def is_enabled(self) -> bool:
        return self._settings_loader().enabled

    def upsert(
        self,
        inbox_item_id: int,
        embedding: EmbeddingResult,
        content_hash: str,
    ) -> str:
        settings = self._settings_loader()
        if not settings.enabled:
            raise VectorStoreError("Vector Store 未启用")

        collection_name = self.collection_name(
            settings.collection_prefix,
            embedding.model,
            embedding.dimension,
        )
        client = self._client(settings)
        self._ensure_collection(client, collection_name, embedding.dimension)

        payload = {
            "inboxItemId": inbox_item_id,
            "embeddingModel": embedding.model,
            "contentHash": content_hash,
            "indexedTime": datetime.now(UTC).isoformat(),
        }
        try:
            result = client.upsert(
                collection_name=collection_name,
                points=[
                    models.PointStruct(
                        id=inbox_item_id,
                        vector=embedding.embedding,
                        payload=payload,
                    )
                ],
                wait=True,
            )
            self._validate_update_result(result)
        except VectorStoreError:
            raise
        except Exception as exception:
            self._raise_controlled("Qdrant Upsert 失败", exception)
        return collection_name

    def delete(self, inbox_item_id: int) -> None:
        settings = self._settings_loader()
        if not settings.enabled:
            return

        client = self._client(settings)
        managed_collection = re.compile(
            rf"^{re.escape(settings.collection_prefix)}__m_[0-9a-f]{{64}}__d_[1-9][0-9]*$"
        )
        try:
            collections_response = client.get_collections()
            collections = getattr(collections_response, "collections", None)
            if not isinstance(collections, list):
                raise VectorStoreInvalidResponseError("Qdrant Collection 列表无效")

            for collection in collections:
                collection_name = getattr(collection, "name", None)
                if not isinstance(collection_name, str):
                    raise VectorStoreInvalidResponseError("Qdrant Collection 名称无效")
                if managed_collection.fullmatch(collection_name) is None:
                    continue
                result = client.delete(
                    collection_name=collection_name,
                    points_selector=models.PointIdsList(points=[inbox_item_id]),
                    wait=True,
                )
                self._validate_update_result(result)
        except VectorStoreError:
            raise
        except Exception as exception:
            self._raise_controlled("Qdrant Delete 失败", exception)

    @staticmethod
    def collection_name(prefix: str, model: str, dimension: int) -> str:
        """模型完整 SHA-256 与维度共同隔离向量空间，避免同维模型被静默混用。"""

        model_hash = hashlib.sha256(model.encode("utf-8")).hexdigest()
        return f"{prefix}__m_{model_hash}__d_{dimension}"

    def _ensure_collection(
        self,
        client: Any,
        collection_name: str,
        dimension: int,
    ) -> None:
        try:
            exists = client.collection_exists(collection_name)
            if not isinstance(exists, bool):
                raise VectorStoreInvalidResponseError("Qdrant Collection 状态无效")
            if not exists:
                try:
                    client.create_collection(
                        collection_name=collection_name,
                        vectors_config=models.VectorParams(
                            size=dimension,
                            distance=models.Distance.COSINE,
                        ),
                    )
                except Exception as create_exception:
                    # 多个后台任务可能同时首次创建；若另一个任务已经成功，则继续兼容性检查。
                    if not client.collection_exists(collection_name):
                        self._raise_controlled(
                            "Qdrant Collection 创建失败",
                            create_exception,
                        )

            info = client.get_collection(collection_name)
            vectors = getattr(
                getattr(getattr(info, "config", None), "params", None),
                "vectors",
                None,
            )
            if not isinstance(vectors, models.VectorParams):
                raise VectorStoreCompatibilityError(
                    "Qdrant Collection 不是当前支持的单向量配置"
                )
            if vectors.size != dimension or vectors.distance != models.Distance.COSINE:
                # 派生索引也不能被代码自动 DROP；运维应选择新前缀或显式处理旧数据。
                raise VectorStoreCompatibilityError(
                    "Qdrant Collection 的维度或距离与当前 Embedding 不兼容"
                )
        except VectorStoreError:
            raise
        except Exception as exception:
            self._raise_controlled("Qdrant Collection 检查失败", exception)

    def _client(self, settings: VectorStoreSettings) -> Any:
        with self._clients_lock:
            client = self._clients.get(settings)
            if client is None:
                try:
                    client = self._client_factory(settings)
                except Exception as exception:
                    self._raise_controlled("Qdrant Client 创建失败", exception)
                self._clients[settings] = client
            return client

    def _create_client(self, settings: VectorStoreSettings) -> QdrantClient:
        return QdrantClient(
            url=settings.url,
            api_key=settings.api_key,
            timeout=settings.timeout_seconds,
            prefer_grpc=False,
        )

    def _validate_update_result(self, result: Any) -> None:
        status = getattr(result, "status", None)
        if status not in {models.UpdateStatus.ACKNOWLEDGED, models.UpdateStatus.COMPLETED}:
            raise VectorStoreInvalidResponseError("Qdrant 写入状态无效")

    def _raise_controlled(self, message: str, exception: Exception) -> None:
        current: BaseException | None = exception
        visited: set[int] = set()
        while current is not None and id(current) not in visited:
            visited.add(id(current))
            if isinstance(current, (TimeoutError, httpx.TimeoutException)):
                raise VectorStoreTimeoutError("Qdrant 请求超时") from exception
            current = current.__cause__ or current.__context__
        # 不回显 URL、API Key、Qdrant 响应正文或向量内容。
        raise VectorStoreError(message) from exception
