import hashlib
import math

import httpx
import pytest
from fastapi.testclient import TestClient
from qdrant_client import QdrantClient, models

from app.config import VectorStoreConfigurationError, VectorStoreSettings
from app.main import app, get_vector_index_service
from app.schemas.embedding import EmbeddingResult
from app.schemas.vector_index import VectorIndexRequest
from app.services.vector_index_service import VectorIndexService
from app.services.vector_store_service import (
    VectorStoreCompatibilityError,
    VectorStoreError,
    VectorStoreService,
    VectorStoreTimeoutError,
)


client = TestClient(app)


class FakeEmbeddingService:
    def __init__(
        self,
        results: list[EmbeddingResult] | None = None,
        exception: RuntimeError | None = None,
    ) -> None:
        self.results = list(results or [])
        self.exception = exception
        self.calls = 0

    def embed(self, request) -> EmbeddingResult:
        self.calls += 1
        if self.exception is not None:
            raise self.exception
        return self.results.pop(0)


@pytest.fixture(autouse=True)
def clear_dependency_overrides():
    yield
    app.dependency_overrides.clear()


def enabled_settings(prefix: str = "lifeinbox_items") -> VectorStoreSettings:
    return VectorStoreSettings(
        enabled=True,
        url="http://qdrant.test:6333",
        collection_prefix=prefix,
        api_key=None,
        timeout_seconds=3,
    )


def disabled_settings() -> VectorStoreSettings:
    return VectorStoreSettings(
        enabled=False,
        url="http://127.0.0.1:6333",
        collection_prefix="lifeinbox_items",
        api_key=None,
        timeout_seconds=5,
    )


def result(model: str = "embedding-model", vector=None) -> EmbeddingResult:
    values = vector or [0.1, -0.2, 0.3]
    return EmbeddingResult(model=model, dimension=len(values), embedding=values)


def cosine_normalized(vector: list[float]) -> list[float]:
    length = math.sqrt(sum(value * value for value in vector))
    return [value / length for value in vector]


def services(
    embedding_results: list[EmbeddingResult],
    qdrant: QdrantClient | None = None,
) -> tuple[VectorIndexService, VectorStoreService, QdrantClient]:
    local_qdrant = qdrant or QdrantClient(":memory:")
    vector_store = VectorStoreService(
        settings_loader=enabled_settings,
        client_factory=lambda settings: local_qdrant,
    )
    index_service = VectorIndexService(
        FakeEmbeddingService(embedding_results),
        vector_store,
    )
    return index_service, vector_store, local_qdrant


def test_first_index_creates_cosine_collection_and_minimal_point() -> None:
    service, vector_store, qdrant = services([result()])

    indexed = service.index(
        VectorIndexRequest(inboxItemId=123, text="  Redis 分布式锁  ")
    )

    expected_collection = vector_store.collection_name(
        "lifeinbox_items", "embedding-model", 3
    )
    assert indexed.indexed is True
    assert indexed.collection == expected_collection
    collection = qdrant.get_collection(expected_collection)
    assert collection.config.params.vectors.size == 3
    assert collection.config.params.vectors.distance == models.Distance.COSINE

    points = qdrant.retrieve(
        expected_collection,
        ids=[123],
        with_payload=True,
        with_vectors=True,
    )
    assert len(points) == 1
    assert points[0].id == 123
    assert points[0].vector == pytest.approx(cosine_normalized([0.1, -0.2, 0.3]))
    assert points[0].payload["inboxItemId"] == 123
    assert points[0].payload["embeddingModel"] == "embedding-model"
    assert points[0].payload["contentHash"] == hashlib.sha256(
        "Redis 分布式锁".encode("utf-8")
    ).hexdigest()
    assert "indexedTime" in points[0].payload
    assert "text" not in points[0].payload


def test_existing_compatible_collection_is_reused_without_recreation() -> None:
    service, vector_store, qdrant = services([result()])
    collection = vector_store.collection_name("lifeinbox_items", "embedding-model", 3)
    qdrant.create_collection(
        collection,
        vectors_config=models.VectorParams(size=3, distance=models.Distance.COSINE),
    )
    qdrant.upsert(
        collection,
        [models.PointStruct(id=999, vector=[0.0, 0.0, 1.0])],
        wait=True,
    )

    service.index(VectorIndexRequest(inboxItemId=123, text="正文"))

    assert qdrant.count(collection, exact=True).count == 2
    assert qdrant.retrieve(collection, ids=[999])


def test_existing_collection_dimension_mismatch_is_not_dropped() -> None:
    service, vector_store, qdrant = services([result()])
    collection = vector_store.collection_name("lifeinbox_items", "embedding-model", 3)
    qdrant.create_collection(
        collection,
        vectors_config=models.VectorParams(size=2, distance=models.Distance.COSINE),
    )

    with pytest.raises(VectorStoreCompatibilityError):
        service.index(VectorIndexRequest(inboxItemId=123, text="正文"))

    assert qdrant.collection_exists(collection)
    assert qdrant.get_collection(collection).config.params.vectors.size == 2
    assert qdrant.count(collection, exact=True).count == 0


def test_same_point_id_is_reupserted_instead_of_duplicated() -> None:
    service, vector_store, qdrant = services(
        [result(vector=[0.1, 0.2, 0.3]), result(vector=[0.9, 0.8, 0.7])]
    )
    request = VectorIndexRequest(inboxItemId=123, text="第一版正文")
    first = service.index(request)
    second = service.index(
        VectorIndexRequest(inboxItemId=123, text="第二版正文")
    )

    assert first.collection == second.collection
    assert qdrant.count(first.collection, exact=True).count == 1
    point = qdrant.retrieve(
        first.collection,
        ids=[123],
        with_payload=True,
        with_vectors=True,
    )[0]
    # Qdrant 对 Cosine Collection 归一化存储，但第二次 Upsert 的方向必须替换第一版。
    assert point.vector == pytest.approx(cosine_normalized([0.9, 0.8, 0.7]))
    assert point.payload["contentHash"] == hashlib.sha256(
        "第二版正文".encode("utf-8")
    ).hexdigest()


def test_different_embedding_models_use_different_collections() -> None:
    service, _, qdrant = services(
        [result(model="model-a"), result(model="model-b")]
    )

    first = service.index(VectorIndexRequest(inboxItemId=123, text="正文 A"))
    second = service.index(VectorIndexRequest(inboxItemId=123, text="正文 B"))
    assert first.collection != second.collection
    assert qdrant.collection_exists(first.collection)
    assert qdrant.collection_exists(second.collection)
    assert qdrant.retrieve(first.collection, ids=[123])[0].payload["embeddingModel"] == "model-a"
    assert qdrant.retrieve(second.collection, ids=[123])[0].payload["embeddingModel"] == "model-b"


def test_delete_is_idempotent_and_cleans_all_managed_model_collections() -> None:
    service, _, qdrant = services(
        [result(model="model-a"), result(model="model-b")]
    )
    first = service.index(VectorIndexRequest(inboxItemId=123, text="正文 A"))
    second = service.index(VectorIndexRequest(inboxItemId=123, text="正文 B"))
    qdrant.create_collection(
        "lifeinbox_items__m_unrelated",
        vectors_config=models.VectorParams(size=3, distance=models.Distance.COSINE),
    )
    qdrant.upsert(
        "lifeinbox_items__m_unrelated",
        [models.PointStruct(id=123, vector=[1.0, 0.0, 0.0])],
        wait=True,
    )

    assert service.delete(123).deleted is True
    assert service.delete(123).deleted is True

    assert qdrant.retrieve(first.collection, ids=[123]) == []
    assert qdrant.retrieve(second.collection, ids=[123]) == []
    assert qdrant.retrieve("lifeinbox_items__m_unrelated", ids=[123])


def test_disabled_store_skips_embedding_and_qdrant() -> None:
    embedding = FakeEmbeddingService([result()])
    vector_store = VectorStoreService(settings_loader=disabled_settings)
    service = VectorIndexService(embedding, vector_store)

    indexed = service.index(VectorIndexRequest(inboxItemId=123, text="正文"))
    deleted = service.delete(123)

    assert indexed.indexed is False
    assert deleted.deleted is False
    assert embedding.calls == 0


def test_embedding_failure_does_not_create_partial_collection_or_point() -> None:
    qdrant = QdrantClient(":memory:")
    embedding = FakeEmbeddingService(exception=RuntimeError("embedding failed"))
    vector_store = VectorStoreService(
        settings_loader=enabled_settings,
        client_factory=lambda settings: qdrant,
    )
    service = VectorIndexService(embedding, vector_store)

    with pytest.raises(RuntimeError, match="embedding failed"):
        service.index(VectorIndexRequest(inboxItemId=123, text="正文"))

    assert qdrant.get_collections().collections == []


def test_qdrant_unavailable_is_lazy_and_health_still_works() -> None:
    vector_store = VectorStoreService(
        settings_loader=enabled_settings,
        client_factory=lambda settings: (_ for _ in ()).throw(
            ConnectionError("secret qdrant address")
        ),
    )
    service = VectorIndexService(FakeEmbeddingService([result()]), vector_store)
    app.dependency_overrides[get_vector_index_service] = lambda: service

    health = client.get("/health")
    index = client.post(
        "/vector/index",
        json={"inboxItemId": 123, "text": "正文"},
    )

    assert health.status_code == 200
    assert index.status_code == 503
    assert index.json() == {"detail": "Vector Store 暂不可用"}
    assert "secret qdrant address" not in index.text


def test_vector_index_and_delete_internal_endpoints() -> None:
    service, _, _ = services([result()])
    app.dependency_overrides[get_vector_index_service] = lambda: service

    indexed = client.post(
        "/vector/index",
        json={"inboxItemId": 123, "text": "正文"},
    )
    deleted = client.delete("/vector/index/123")

    assert indexed.status_code == 200
    assert indexed.json()["inboxItemId"] == 123
    assert indexed.json()["indexed"] is True
    assert deleted.status_code == 200
    assert deleted.json() == {"inboxItemId": 123, "deleted": True}


def test_vector_index_endpoint_rejects_invalid_input() -> None:
    assert client.post(
        "/vector/index", json={"inboxItemId": 0, "text": "正文"}
    ).status_code == 422
    assert client.post(
        "/vector/index", json={"inboxItemId": 1, "text": "   "}
    ).status_code == 422
    assert client.delete("/vector/index/0").status_code == 422


def test_vector_store_settings_default_to_disabled_without_qdrant_requirements(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("LIFEINBOX_VECTOR_STORE_ENABLED", raising=False)
    monkeypatch.setenv("LIFEINBOX_QDRANT_URL", "not-a-url")
    monkeypatch.setenv("LIFEINBOX_QDRANT_API_KEY", "must-not-be-read")

    settings = VectorStoreSettings.from_environment()

    assert settings.enabled is False
    assert settings.api_key is None


def test_enabled_vector_store_validates_url_collection_and_timeout(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("LIFEINBOX_VECTOR_STORE_ENABLED", "true")
    monkeypatch.setenv("LIFEINBOX_QDRANT_URL", "http://127.0.0.1:6333/")
    monkeypatch.setenv("LIFEINBOX_QDRANT_COLLECTION", "lifeinbox_items")
    monkeypatch.setenv("LIFEINBOX_QDRANT_TIMEOUT_SECONDS", "NaN")

    with pytest.raises(VectorStoreConfigurationError):
        VectorStoreSettings.from_environment()


def test_qdrant_timeout_is_mapped_to_controlled_error() -> None:
    class TimeoutClient:
        def get_collections(self):
            request = httpx.Request("GET", "http://qdrant.test/collections")
            raise httpx.ReadTimeout("secret timeout", request=request)

    vector_store = VectorStoreService(
        settings_loader=enabled_settings,
        client_factory=lambda settings: TimeoutClient(),
    )

    with pytest.raises(VectorStoreTimeoutError):
        vector_store.delete(123)
