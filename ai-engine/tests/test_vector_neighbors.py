from collections.abc import Callable
from types import SimpleNamespace

import httpx
import pytest
from fastapi.testclient import TestClient
from qdrant_client import QdrantClient, models

import app.main as main_module
from app.config import VectorStoreSettings
from app.main import app, get_vector_neighbor_service
from app.schemas.embedding import EmbeddingResult
from app.schemas.vector_neighbor import VectorNeighborRequest
from app.services.vector_neighbor_service import VectorNeighborService
from app.services.vector_store_service import VectorStoreService


client = TestClient(app)


@pytest.fixture(autouse=True)
def clear_dependency_overrides():
    yield
    app.dependency_overrides.clear()


def enabled_settings() -> VectorStoreSettings:
    return VectorStoreSettings(
        enabled=True,
        url="http://qdrant.test:6333",
        collection_prefix="lifeinbox_items",
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


def embedding(vector: list[float]) -> EmbeddingResult:
    return EmbeddingResult(
        model="embedding-model",
        dimension=len(vector),
        embedding=vector,
    )


def vector_store(
    qdrant: object,
    settings_loader: Callable[[], VectorStoreSettings] = enabled_settings,
) -> VectorStoreService:
    return VectorStoreService(
        settings_loader=settings_loader,
        client_factory=lambda settings: qdrant,
    )


def neighbor_service(store: VectorStoreService) -> VectorNeighborService:
    return VectorNeighborService(store, lambda: "embedding-model")


def test_vector_neighbors_reuses_source_vector_and_filters_self() -> None:
    qdrant = QdrantClient(":memory:")
    store = vector_store(qdrant)
    store.upsert(123, embedding([1.0, 0.0, 0.0]), "a" * 64)
    store.upsert(456, embedding([0.9, 0.1, 0.0]), "b" * 64)
    store.upsert(789, embedding([0.0, 1.0, 0.0]), "c" * 64)

    response = neighbor_service(store).find_neighbors(
        VectorNeighborRequest(inboxItemId=123, limit=2)
    )

    assert response.source_indexed is True
    assert [candidate.inbox_item_id for candidate in response.results] == [456, 789]
    assert all(candidate.inbox_item_id != 123 for candidate in response.results)
    assert response.results[0].score > response.results[1].score


def test_vector_neighbors_endpoint_returns_camel_case_contract() -> None:
    qdrant = QdrantClient(":memory:")
    store = vector_store(qdrant)
    store.upsert(123, embedding([1.0, 0.0]), "a" * 64)
    store.upsert(456, embedding([0.8, 0.2]), "b" * 64)
    app.dependency_overrides[get_vector_neighbor_service] = lambda: neighbor_service(store)

    response = client.post(
        "/vector/neighbors",
        json={"inboxItemId": 123, "limit": 20},
    )

    assert response.status_code == 200
    assert response.json()["sourceIndexed"] is True
    assert response.json()["results"][0]["inboxItemId"] == 456


def test_missing_source_point_is_normal_empty_result() -> None:
    qdrant = QdrantClient(":memory:")
    store = vector_store(qdrant)
    store.upsert(456, embedding([1.0, 0.0]), "a" * 64)
    app.dependency_overrides[get_vector_neighbor_service] = lambda: neighbor_service(store)

    response = client.post(
        "/vector/neighbors",
        json={"inboxItemId": 123, "limit": 20},
    )

    assert response.status_code == 200
    assert response.json() == {"sourceIndexed": False, "results": []}


@pytest.mark.parametrize(
    "body",
    [
        {"inboxItemId": 0, "limit": 20},
        {"inboxItemId": -1, "limit": 20},
        {"inboxItemId": 123, "limit": 0},
        {"inboxItemId": 123, "limit": 21},
    ],
)
def test_vector_neighbors_rejects_invalid_id_or_limit(body: dict) -> None:
    assert client.post("/vector/neighbors", json=body).status_code == 422


def test_vector_neighbors_rejects_non_finite_qdrant_score() -> None:
    collection = VectorStoreService.collection_name(
        "lifeinbox_items", "embedding-model", 2
    )

    class NonFiniteScoreClient:
        def get_collections(self):
            return SimpleNamespace(
                collections=[SimpleNamespace(name=collection)]
            )

        def get_collection(self, collection_name):
            return SimpleNamespace(
                config=SimpleNamespace(
                    params=SimpleNamespace(
                        vectors=models.VectorParams(
                            size=2,
                            distance=models.Distance.COSINE,
                        )
                    )
                )
            )

        def retrieve(self, **kwargs):
            return [
                SimpleNamespace(
                    id=123,
                    vector=[1.0, 0.0],
                    payload={"embeddingModel": "embedding-model"},
                )
            ]

        def query_points(self, **kwargs):
            return SimpleNamespace(
                points=[SimpleNamespace(id=456, score=float("nan"))]
            )

    app.dependency_overrides[get_vector_neighbor_service] = lambda: neighbor_service(
        vector_store(NonFiniteScoreClient())
    )

    response = client.post(
        "/vector/neighbors",
        json={"inboxItemId": 123, "limit": 20},
    )

    assert response.status_code == 502
    assert response.json() == {"detail": "Vector Store 返回无效响应"}


def test_disabled_vector_store_is_controlled_without_model_or_embedding_call() -> None:
    model_loader_calls = 0

    def forbidden_model_loader() -> str:
        nonlocal model_loader_calls
        model_loader_calls += 1
        raise AssertionError("disabled path must stop before model config")

    service = VectorNeighborService(
        vector_store(QdrantClient(":memory:"), disabled_settings),
        forbidden_model_loader,
    )
    app.dependency_overrides[get_vector_neighbor_service] = lambda: service

    response = client.post(
        "/vector/neighbors",
        json={"inboxItemId": 123, "limit": 20},
    )

    assert response.status_code == 503
    assert model_loader_calls == 0


def test_missing_collection_uses_existing_controlled_vector_error() -> None:
    app.dependency_overrides[get_vector_neighbor_service] = lambda: neighbor_service(
        vector_store(QdrantClient(":memory:"))
    )

    response = client.post(
        "/vector/neighbors",
        json={"inboxItemId": 123, "limit": 20},
    )

    assert response.status_code == 404
    assert response.json() == {"detail": "Semantic Search 索引不存在"}


def test_qdrant_timeout_is_not_reported_as_source_missing() -> None:
    class TimeoutClient:
        def get_collections(self):
            request = httpx.Request("GET", "http://qdrant.test/collections")
            raise httpx.ReadTimeout("secret timeout", request=request)

    app.dependency_overrides[get_vector_neighbor_service] = lambda: neighbor_service(
        vector_store(TimeoutClient())
    )

    response = client.post(
        "/vector/neighbors",
        json={"inboxItemId": 123, "limit": 20},
    )

    assert response.status_code == 504
    assert response.json() == {"detail": "Vector Store 请求超时"}
    assert "secret timeout" not in response.text


def test_neighbor_endpoint_never_calls_embedding_provider(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    qdrant = QdrantClient(":memory:")
    store = vector_store(qdrant)
    store.upsert(123, embedding([1.0, 0.0]), "a" * 64)
    store.upsert(456, embedding([0.9, 0.1]), "b" * 64)
    app.dependency_overrides[get_vector_neighbor_service] = lambda: neighbor_service(store)

    def forbidden_embedding_call(request):
        raise AssertionError("Neighbor Search must reuse the stored source vector")

    monkeypatch.setattr(main_module.embedding_service, "embed", forbidden_embedding_call)

    response = client.post(
        "/vector/neighbors",
        json={"inboxItemId": 123, "limit": 20},
    )

    assert response.status_code == 200
    assert response.json()["results"][0]["inboxItemId"] == 456
