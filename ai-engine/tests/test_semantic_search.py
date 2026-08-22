from collections.abc import Callable

import pytest
from fastapi.testclient import TestClient
from qdrant_client import QdrantClient, models

from app.config import VectorStoreSettings
from app.main import app, get_semantic_search_service
from app.schemas.embedding import EmbeddingResult
from app.schemas.semantic_search import (
    SemanticSearchRequest,
    SemanticSearchResponse,
)
from app.services.embedding_client import EmbeddingServiceError
from app.services.semantic_search_service import SemanticSearchService
from app.services.vector_store_service import VectorStoreService


client = TestClient(app)


class FakeEmbeddingService:
    def __init__(
        self,
        result: EmbeddingResult | None = None,
        exception: RuntimeError | None = None,
    ) -> None:
        self.result = result
        self.exception = exception
        self.calls = 0

    def embed(self, request) -> EmbeddingResult:
        self.calls += 1
        if self.exception is not None:
            raise self.exception
        assert self.result is not None
        return self.result


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


def embedding(
    model: str = "embedding-model",
    vector: list[float] | None = None,
) -> EmbeddingResult:
    values = vector or [1.0, 0.0, 0.0]
    return EmbeddingResult(model=model, dimension=len(values), embedding=values)


def vector_store(
    qdrant: QdrantClient,
    settings_loader: Callable[[], VectorStoreSettings] = enabled_settings,
) -> VectorStoreService:
    return VectorStoreService(
        settings_loader=settings_loader,
        client_factory=lambda settings: qdrant,
    )


def test_semantic_search_returns_candidate_ids_and_similarity_order() -> None:
    qdrant = QdrantClient(":memory:")
    store = vector_store(qdrant)
    store.upsert(123, embedding(vector=[1.0, 0.0, 0.0]), "a" * 64)
    store.upsert(456, embedding(vector=[0.0, 1.0, 0.0]), "b" * 64)
    service = SemanticSearchService(
        FakeEmbeddingService(embedding(vector=[0.9, 0.1, 0.0])),
        store,
    )

    response = service.search(
        SemanticSearchRequest(query="防止接口重复请求的方案", limit=2)
    )

    assert [candidate.inbox_item_id for candidate in response.results] == [123, 456]
    assert response.results[0].score > response.results[1].score


def test_semantic_search_internal_endpoint_returns_camel_case_candidates() -> None:
    qdrant = QdrantClient(":memory:")
    store = vector_store(qdrant)
    store.upsert(123, embedding(), "a" * 64)
    service = SemanticSearchService(FakeEmbeddingService(embedding()), store)
    app.dependency_overrides[get_semantic_search_service] = lambda: service

    response = client.post(
        "/vector/search",
        json={"query": "Redis 幂等方案", "limit": 20},
    )

    assert response.status_code == 200
    assert response.json()["results"][0]["inboxItemId"] == 123
    assert isinstance(response.json()["results"][0]["score"], float)


@pytest.mark.parametrize(
    "body",
    [
        {"query": ""},
        {"query": "   "},
        {"query": "x" * 201},
        {"query": "Redis", "limit": 0},
        {"query": "Redis", "limit": 101},
    ],
)
def test_semantic_search_rejects_invalid_query_or_limit(body: dict) -> None:
    assert client.post("/vector/search", json=body).status_code == 422


def test_semantic_search_maps_embedding_failure() -> None:
    service = SemanticSearchService(
        FakeEmbeddingService(exception=EmbeddingServiceError("provider secret")),
        vector_store(QdrantClient(":memory:")),
    )
    app.dependency_overrides[get_semantic_search_service] = lambda: service

    response = client.post("/vector/search", json={"query": "Redis"})

    assert response.status_code == 503
    assert response.json() == {"detail": "Embedding 服务暂不可用"}
    assert "provider secret" not in response.text


def test_disabled_vector_store_fails_without_query_embedding() -> None:
    fake_embedding = FakeEmbeddingService(embedding())
    service = SemanticSearchService(
        fake_embedding,
        vector_store(QdrantClient(":memory:"), disabled_settings),
    )
    app.dependency_overrides[get_semantic_search_service] = lambda: service

    response = client.post("/vector/search", json={"query": "Redis"})

    assert response.status_code == 503
    assert response.json() == {"detail": "Semantic Search 未启用"}
    assert fake_embedding.calls == 0
    assert client.get("/health").status_code == 200


def test_qdrant_down_returns_controlled_error() -> None:
    store = VectorStoreService(
        settings_loader=enabled_settings,
        client_factory=lambda settings: (_ for _ in ()).throw(
            ConnectionError("secret qdrant address")
        ),
    )
    service = SemanticSearchService(FakeEmbeddingService(embedding()), store)
    app.dependency_overrides[get_semantic_search_service] = lambda: service

    response = client.post("/vector/search", json={"query": "Redis"})

    assert response.status_code == 503
    assert response.json() == {"detail": "Vector Store 暂不可用"}
    assert "secret qdrant address" not in response.text


def test_missing_collection_is_controlled_and_not_created() -> None:
    qdrant = QdrantClient(":memory:")
    service = SemanticSearchService(
        FakeEmbeddingService(embedding()),
        vector_store(qdrant),
    )
    app.dependency_overrides[get_semantic_search_service] = lambda: service

    response = client.post("/vector/search", json={"query": "Redis"})

    assert response.status_code == 404
    assert response.json() == {"detail": "Semantic Search 索引不存在"}
    assert qdrant.get_collections().collections == []


def test_model_mismatch_returns_conflict_without_cross_collection_search() -> None:
    qdrant = QdrantClient(":memory:")
    store = vector_store(qdrant)
    store.upsert(123, embedding(model="model-a"), "a" * 64)
    service = SemanticSearchService(
        FakeEmbeddingService(embedding(model="model-b")),
        store,
    )
    app.dependency_overrides[get_semantic_search_service] = lambda: service

    response = client.post("/vector/search", json={"query": "Redis"})

    assert response.status_code == 409
    assert response.json() == {
        "detail": "Vector Collection 与当前 Embedding 不兼容"
    }
    assert qdrant.count(
        store.collection_name("lifeinbox_items", "model-a", 3), exact=True
    ).count == 1


def test_dimension_mismatch_returns_conflict() -> None:
    qdrant = QdrantClient(":memory:")
    store = vector_store(qdrant)
    store.upsert(123, embedding(model="model-a", vector=[1.0, 0.0, 0.0]), "a" * 64)
    service = SemanticSearchService(
        FakeEmbeddingService(
            embedding(model="model-a", vector=[1.0, 0.0, 0.0, 0.0])
        ),
        store,
    )
    app.dependency_overrides[get_semantic_search_service] = lambda: service

    response = client.post("/vector/search", json={"query": "Redis"})

    assert response.status_code == 409
    assert response.json() == {
        "detail": "Vector Collection 与当前 Embedding 不兼容"
    }


def test_distance_mismatch_returns_conflict_without_modifying_collection() -> None:
    qdrant = QdrantClient(":memory:")
    store = vector_store(qdrant)
    collection = store.collection_name("lifeinbox_items", "embedding-model", 3)
    qdrant.create_collection(
        collection,
        vectors_config=models.VectorParams(size=3, distance=models.Distance.DOT),
    )
    service = SemanticSearchService(FakeEmbeddingService(embedding()), store)
    app.dependency_overrides[get_semantic_search_service] = lambda: service

    response = client.post("/vector/search", json={"query": "Redis"})

    assert response.status_code == 409
    assert qdrant.get_collection(collection).config.params.vectors.distance == models.Distance.DOT


def test_empty_vector_result_is_a_normal_empty_response() -> None:
    qdrant = QdrantClient(":memory:")
    store = vector_store(qdrant)
    collection = store.collection_name("lifeinbox_items", "embedding-model", 3)
    qdrant.create_collection(
        collection,
        vectors_config=models.VectorParams(size=3, distance=models.Distance.COSINE),
    )
    service = SemanticSearchService(FakeEmbeddingService(embedding()), store)

    response = service.search(SemanticSearchRequest(query="Redis"))

    assert response == SemanticSearchResponse(results=[])
