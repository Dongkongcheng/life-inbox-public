import json
import math

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import RerankConfigurationError, RerankSettings
from app.main import app, get_rerank_service
from app.schemas.rerank import RerankRequest, RerankResponse
from app.services.rerank_client import (
    RerankClient,
    RerankInvalidResponseError,
    RerankServiceError,
    RerankTimeoutError,
)
from app.services.rerank_service import RerankService


client = TestClient(app)


@pytest.fixture(autouse=True)
def clear_dependency_overrides():
    yield
    app.dependency_overrides.clear()


def rerank_settings() -> RerankSettings:
    return RerankSettings(
        api_key="test-api-key",
        model="test-rerank-model",
        base_url="https://provider.test/v1",
        timeout_seconds=3,
    )


def service_with_transport(transport: httpx.BaseTransport) -> RerankService:
    return RerankService(
        RerankClient(
            settings_loader=rerank_settings,
            transport=transport,
        )
    )


def request(documents: list[dict] | None = None, top_k: int = 3) -> RerankRequest:
    return RerankRequest.model_validate(
        {
            "query": "怎样防止接口重复提交",
            "documents": documents
            if documents is not None
            else [
                {"id": 101, "text": "Redis 缓存雪崩"},
                {"id": 102, "text": "接口幂等与 Redisson"},
                {"id": 103, "text": "Redis 分布式锁"},
            ],
            "topK": top_k,
        }
    )


def test_rerank_endpoint_maps_provider_indexes_to_stable_ids() -> None:
    captured_request: httpx.Request | None = None

    def handler(provider_request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = provider_request
        return httpx.Response(
            200,
            json={
                "object": "list",
                "results": [
                    {"index": 1, "relevance_score": 0.95},
                    {"index": 0, "relevance_score": 0.75},
                    {"index": 2, "relevance_score": 0.5},
                ],
                "model": "test-rerank-model",
                "usage": {"total_tokens": 30},
            },
        )

    app.dependency_overrides[get_rerank_service] = lambda: service_with_transport(
        httpx.MockTransport(handler)
    )

    response = client.post(
        "/rerank",
        json=request().model_dump(by_alias=True),
    )

    assert response.status_code == 200
    assert response.json() == {
        "results": [
            {"id": 102, "score": 0.95},
            {"id": 101, "score": 0.75},
            {"id": 103, "score": 0.5},
        ]
    }
    assert captured_request is not None
    assert str(captured_request.url) == "https://provider.test/v1/reranks"
    assert captured_request.headers["Authorization"] == "Bearer test-api-key"
    assert json.loads(captured_request.content) == {
        "model": "test-rerank-model",
        "query": "怎样防止接口重复提交",
        "documents": [
            "Redis 缓存雪崩",
            "接口幂等与 Redisson",
            "Redis 分布式锁",
        ],
        "top_n": 3,
    }


def test_empty_documents_return_empty_without_loading_provider_configuration() -> None:
    class FailingClient:
        def rerank(self, query: str, documents: list[str], top_n: int):
            raise AssertionError("空候选不应调用 Provider")

    service = RerankService(FailingClient())

    assert service.rerank(request(documents=[], top_k=20)) == RerankResponse(
        results=[]
    )


@pytest.mark.parametrize(
    "body",
    [
        {"query": "", "documents": [], "topK": 20},
        {"query": "   ", "documents": [], "topK": 20},
        {"query": "q", "documents": [{"id": 1, "text": "x" * 2_001}]},
        {
            "query": "q",
            "documents": [{"id": 1, "text": "a"}, {"id": 1, "text": "b"}],
        },
        {
            "query": "q",
            "documents": [
                {"id": index + 1, "text": "text"} for index in range(101)
            ],
        },
        {"query": "q", "documents": [], "topK": 0},
        {"query": "q", "documents": [], "topK": 101},
    ],
)
def test_rerank_rejects_invalid_query_documents_or_limit(body: dict) -> None:
    assert client.post("/rerank", json=body).status_code == 422


def test_partial_provider_result_is_preserved_for_java_to_append_missing_items() -> None:
    service = service_with_transport(
        httpx.MockTransport(
            lambda provider_request: httpx.Response(
                200,
                json={"results": [{"index": 1, "relevance_score": 0.9}]},
            )
        )
    )

    response = service.rerank(request())

    assert [(candidate.id, candidate.score) for candidate in response.results] == [
        (102, 0.9)
    ]


def test_rerank_client_maps_timeout() -> None:
    def handler(provider_request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("secret timeout", request=provider_request)

    with pytest.raises(RerankTimeoutError):
        service_with_transport(httpx.MockTransport(handler)).rerank(request())


@pytest.mark.parametrize("status_code", [401, 403, 429, 500])
def test_rerank_maps_provider_status_without_leaking_secrets(status_code: int) -> None:
    service = service_with_transport(
        httpx.MockTransport(
            lambda provider_request: httpx.Response(
                status_code,
                json={"message": "provider-secret-response"},
            )
        )
    )

    with pytest.raises(RerankServiceError) as exception_info:
        service.rerank(request())

    assert "provider-secret-response" not in str(exception_info.value)
    assert "test-api-key" not in str(exception_info.value)


@pytest.mark.parametrize(
    "provider_body",
    [
        {},
        {"results": [None]},
        {"results": [{"index": -1, "relevance_score": 0.9}]},
        {"results": [{"index": 3, "relevance_score": 0.9}]},
        {
            "results": [
                {"index": 1, "relevance_score": 0.9},
                {"index": 1, "relevance_score": 0.8},
            ]
        },
        {"results": [{"index": 1, "relevance_score": None}]},
        {"results": [{"index": True, "relevance_score": 0.9}]},
        {"results": [{"index": 1, "relevance_score": math.nan}]},
        {"results": [{"index": 1, "relevance_score": math.inf}]},
    ],
)
def test_rerank_rejects_invalid_provider_candidates(provider_body: dict) -> None:
    raw_body = json.dumps(provider_body, allow_nan=True).encode("utf-8")
    service = service_with_transport(
        httpx.MockTransport(
            lambda provider_request: httpx.Response(
                200,
                headers={"Content-Type": "application/json"},
                content=raw_body,
            )
        )
    )

    with pytest.raises(RerankInvalidResponseError):
        service.rerank(request())


def test_rerank_rejects_more_results_than_requested_top_k() -> None:
    service = service_with_transport(
        httpx.MockTransport(
            lambda provider_request: httpx.Response(
                200,
                json={
                    "results": [
                        {"index": 0, "relevance_score": 0.9},
                        {"index": 1, "relevance_score": 0.8},
                    ]
                },
            )
        )
    )

    with pytest.raises(RerankInvalidResponseError):
        service.rerank(request(top_k=1))


def test_rerank_rejects_invalid_json() -> None:
    service = service_with_transport(
        httpx.MockTransport(
            lambda provider_request: httpx.Response(200, content=b"not-json")
        )
    )

    with pytest.raises(RerankInvalidResponseError):
        service.rerank(request())


@pytest.mark.parametrize(
    ("exception", "status_code", "detail"),
    [
        (RerankTimeoutError("timeout"), 504, "Rerank 请求超时"),
        (RerankInvalidResponseError("invalid"), 502, "Rerank 返回结果无效"),
        (RerankServiceError("failed"), 503, "Rerank 服务暂不可用"),
    ],
)
def test_rerank_endpoint_maps_controlled_failures(
    exception: RuntimeError,
    status_code: int,
    detail: str,
) -> None:
    class FailedService:
        def rerank(self, rerank_request: RerankRequest):
            raise exception

    app.dependency_overrides[get_rerank_service] = lambda: FailedService()

    response = client.post("/rerank", json=request().model_dump(by_alias=True))

    assert response.status_code == status_code
    assert response.json() == {"detail": detail}


@pytest.mark.parametrize("enabled", ["false", "true"])
def test_missing_rerank_base_url_does_not_break_startup_and_fails_on_call(
    monkeypatch: pytest.MonkeyPatch,
    enabled: str,
) -> None:
    monkeypatch.setenv("LIFEINBOX_LLM_API_KEY", "test-key")
    monkeypatch.setenv("LIFEINBOX_LLM_BASE_URL", "http://provider.test/v1")
    monkeypatch.setenv("LIFEINBOX_RERANK_MODEL", "rerank-only-model")
    monkeypatch.setenv("LIFEINBOX_RERANK_ENABLED", enabled)
    monkeypatch.delenv("LIFEINBOX_RERANK_BASE_URL", raising=False)

    health_response = client.get("/health")
    rerank_response = client.post(
        "/rerank",
        json=request().model_dump(by_alias=True),
    )

    assert health_response.status_code == 200
    assert rerank_response.status_code == 503
    assert rerank_response.json() == {"detail": "Rerank 配置不完整"}


def test_rerank_settings_use_independent_model_and_timeout(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("LIFEINBOX_LLM_API_KEY", "test-key")
    monkeypatch.setenv("LIFEINBOX_LLM_BASE_URL", "http://llm.test/compatible-mode/v1")
    monkeypatch.setenv(
        "LIFEINBOX_RERANK_BASE_URL",
        "http://rerank.test/compatible-api/v1/",
    )
    monkeypatch.setenv("LIFEINBOX_RERANK_MODEL", "rerank-only-model")
    monkeypatch.setenv("LIFEINBOX_RERANK_TIMEOUT_SECONDS", "6")

    settings = RerankSettings.from_environment()

    assert settings.model == "rerank-only-model"
    assert settings.base_url == "http://rerank.test/compatible-api/v1"
    assert settings.timeout_seconds == 6


@pytest.mark.parametrize(
    "base_url",
    [
        "https://rerank.example/compatible-api/v1",
        "https://rerank.example/compatible-api/v1/",
    ],
)
def test_rerank_client_uses_independent_base_url_and_plural_path(
    monkeypatch: pytest.MonkeyPatch,
    base_url: str,
) -> None:
    captured_url: str | None = None

    def handler(provider_request: httpx.Request) -> httpx.Response:
        nonlocal captured_url
        captured_url = str(provider_request.url)
        return httpx.Response(
            200,
            json={"results": [{"index": 0, "relevance_score": 0.8}]},
        )

    monkeypatch.setenv("LIFEINBOX_LLM_API_KEY", "test-key")
    monkeypatch.setenv(
        "LIFEINBOX_LLM_BASE_URL",
        "https://llm.example/compatible-mode/v1",
    )
    monkeypatch.setenv("LIFEINBOX_RERANK_BASE_URL", base_url)
    monkeypatch.setenv("LIFEINBOX_RERANK_MODEL", "qwen3-rerank")

    service = RerankService(RerankClient(transport=httpx.MockTransport(handler)))
    service.rerank(request(documents=[{"id": 100, "text": "接口幂等"}], top_k=1))

    assert captured_url == "https://rerank.example/compatible-api/v1/reranks"


@pytest.mark.parametrize("timeout", ["NaN", "0", "61"])
def test_rerank_settings_reject_invalid_timeout_without_exposing_secret(
    monkeypatch: pytest.MonkeyPatch,
    timeout: str,
) -> None:
    monkeypatch.setenv("LIFEINBOX_LLM_API_KEY", "secret-test-key")
    monkeypatch.setenv("LIFEINBOX_LLM_BASE_URL", "http://provider.test/v1")
    monkeypatch.setenv("LIFEINBOX_RERANK_BASE_URL", "http://rerank.test/v1")
    monkeypatch.setenv("LIFEINBOX_RERANK_MODEL", "rerank-model")
    monkeypatch.setenv("LIFEINBOX_RERANK_TIMEOUT_SECONDS", timeout)

    with pytest.raises(RerankConfigurationError) as exception_info:
        RerankSettings.from_environment()

    assert "secret-test-key" not in str(exception_info.value)
