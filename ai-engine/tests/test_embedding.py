import json
import math

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.config import EmbeddingConfigurationError, EmbeddingSettings
from app.main import app, get_embedding_service
from app.schemas.embedding import EmbeddingRequest, EmbeddingResult
from app.services.embedding_client import (
    EmbeddingClient,
    EmbeddingInvalidResponseError,
    EmbeddingServiceError,
    EmbeddingTimeoutError,
)
from app.services.embedding_service import EmbeddingService


client = TestClient(app)


class SuccessfulEmbeddingService:
    def embed(self, request: EmbeddingRequest) -> EmbeddingResult:
        return EmbeddingResult(
            model="test-embedding-model",
            dimension=3,
            embedding=[0.125, -0.25, 0.5],
        )


class FailedEmbeddingService:
    def __init__(self, exception: RuntimeError) -> None:
        self._exception = exception

    def embed(self, request: EmbeddingRequest) -> EmbeddingResult:
        raise self._exception


@pytest.fixture(autouse=True)
def clear_dependency_overrides():
    yield
    app.dependency_overrides.clear()


def embedding_settings() -> EmbeddingSettings:
    return EmbeddingSettings(
        api_key="test-api-key",
        model="configured-embedding-model",
        base_url="https://provider.test/v1",
        timeout_seconds=3,
    )


def service_with_transport(transport: httpx.BaseTransport) -> EmbeddingService:
    return EmbeddingService(
        EmbeddingClient(
            settings_loader=embedding_settings,
            transport=transport,
        )
    )


def test_embedding_endpoint_returns_model_dimension_and_vector() -> None:
    app.dependency_overrides[get_embedding_service] = lambda: SuccessfulEmbeddingService()

    response = client.post("/embedding", json={"text": "  Redis 分布式锁  "})

    assert response.status_code == 200
    assert response.json() == {
        "model": "test-embedding-model",
        "dimension": 3,
        "embedding": [0.125, -0.25, 0.5],
    }


@pytest.mark.parametrize("text", ["", "   "])
def test_embedding_rejects_empty_or_blank_text(text: str) -> None:
    response = client.post("/embedding", json={"text": text})

    assert response.status_code == 422


def test_embedding_rejects_text_over_searchable_content_limit() -> None:
    response = client.post("/embedding", json={"text": "x" * 20_001})

    assert response.status_code == 422


def test_embedding_client_calls_openai_compatible_endpoint() -> None:
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(
            200,
            json={
                "model": "provider-embedding-model",
                "data": [{"index": 0, "embedding": [0.1, -0.2, 0.3]}],
            },
        )

    service = service_with_transport(httpx.MockTransport(handler))

    result = service.embed(EmbeddingRequest(text="Redis 分布式锁"))

    assert result == EmbeddingResult(
        model="provider-embedding-model",
        dimension=3,
        embedding=[0.1, -0.2, 0.3],
    )
    assert captured_request is not None
    assert str(captured_request.url) == "https://provider.test/v1/embeddings"
    assert captured_request.headers["Authorization"] == "Bearer test-api-key"
    assert json.loads(captured_request.content) == {
        "model": "configured-embedding-model",
        "input": "Redis 分布式锁",
    }


def test_embedding_client_maps_timeout() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("mock timeout", request=request)

    service = service_with_transport(httpx.MockTransport(handler))

    with pytest.raises(EmbeddingTimeoutError):
        service.embed(EmbeddingRequest(text="正文"))


@pytest.mark.parametrize("status_code", [401, 403, 429, 500])
def test_embedding_client_maps_provider_status_without_leaking_secrets(
    status_code: int,
) -> None:
    service = service_with_transport(
        httpx.MockTransport(
            lambda request: httpx.Response(
                status_code,
                json={"message": "provider-secret-response"},
            )
        )
    )

    with pytest.raises(EmbeddingServiceError) as exception_info:
        service.embed(EmbeddingRequest(text="正文"))

    message = str(exception_info.value)
    assert "provider-secret-response" not in message
    assert "test-api-key" not in message


def test_embedding_client_rejects_invalid_json() -> None:
    service = service_with_transport(
        httpx.MockTransport(
            lambda request: httpx.Response(200, content=b"not-json")
        )
    )

    with pytest.raises(EmbeddingInvalidResponseError):
        service.embed(EmbeddingRequest(text="正文"))


@pytest.mark.parametrize(
    "provider_body",
    [
        {},
        {"model": "m", "data": []},
        {"model": "m", "data": [{"embedding": None}]},
        {"model": "m", "data": [{"embedding": []}]},
        {"model": "m", "data": [{"embedding": [None]}]},
        {"model": "m", "data": [{"embedding": [True]}]},
        {"model": "m", "data": [{"embedding": ["0.1"]}]},
        {"model": "m", "data": [{"embedding": [math.nan]}]},
        {"model": "m", "data": [{"embedding": [math.inf]}]},
        {"model": "m", "data": [{"embedding": [-math.inf]}]},
        {"model": " ", "data": [{"embedding": [0.1]}]},
        {
            "model": "m",
            "data": [{"embedding": [0.1]}, {"embedding": [0.2]}],
        },
    ],
)
def test_embedding_service_rejects_missing_empty_or_invalid_vector(
    provider_body: dict,
) -> None:
    # 某些上游可能返回非标准 JSON 数值；绕过 httpx 的严格编码器以验证边界拒绝逻辑。
    raw_body = json.dumps(provider_body, allow_nan=True).encode("utf-8")
    service = service_with_transport(
        httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                headers={"Content-Type": "application/json"},
                content=raw_body,
            )
        )
    )

    with pytest.raises(EmbeddingInvalidResponseError):
        service.embed(EmbeddingRequest(text="正文"))


def test_embedding_result_rejects_dimension_mismatch() -> None:
    with pytest.raises(ValidationError):
        EmbeddingResult(model="m", dimension=2, embedding=[0.1])


@pytest.mark.parametrize(
    ("exception", "status_code", "detail"),
    [
        (EmbeddingTimeoutError("timeout"), 504, "Embedding 请求超时"),
        (
            EmbeddingInvalidResponseError("invalid"),
            502,
            "Embedding 返回的向量无效",
        ),
        (EmbeddingServiceError("failed"), 503, "Embedding 服务暂不可用"),
    ],
)
def test_embedding_endpoint_maps_controlled_failures(
    exception: RuntimeError,
    status_code: int,
    detail: str,
) -> None:
    app.dependency_overrides[get_embedding_service] = lambda: FailedEmbeddingService(
        exception
    )

    response = client.post("/embedding", json={"text": "正文"})

    assert response.status_code == status_code
    assert response.json() == {"detail": detail}


def test_embedding_not_configured_does_not_break_startup_or_health(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("LIFEINBOX_EMBEDDING_MODEL", raising=False)
    monkeypatch.setenv("LIFEINBOX_LLM_API_KEY", "test-key")
    monkeypatch.setenv("LIFEINBOX_LLM_BASE_URL", "http://provider.test/v1")
    monkeypatch.setenv("LIFEINBOX_LLM_TIMEOUT_SECONDS", "3")

    health_response = client.get("/health")
    embedding_response = client.post("/embedding", json={"text": "正文"})

    assert health_response.status_code == 200
    assert embedding_response.status_code == 503
    assert embedding_response.json() == {"detail": "Embedding 配置不完整"}


def test_embedding_model_is_independent_from_chat_model(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("LIFEINBOX_LLM_API_KEY", "test-key")
    monkeypatch.delenv("LIFEINBOX_LLM_MODEL", raising=False)
    monkeypatch.setenv("LIFEINBOX_EMBEDDING_MODEL", "embedding-only-model")
    monkeypatch.setenv("LIFEINBOX_LLM_BASE_URL", "http://provider.test/v1/")
    monkeypatch.setenv("LIFEINBOX_LLM_TIMEOUT_SECONDS", "3")

    settings = EmbeddingSettings.from_environment()

    assert settings.model == "embedding-only-model"
    assert settings.base_url == "http://provider.test/v1"


def test_embedding_settings_reject_invalid_timeout_without_exposing_values(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("LIFEINBOX_LLM_API_KEY", "secret-test-key")
    monkeypatch.setenv("LIFEINBOX_EMBEDDING_MODEL", "embedding-model")
    monkeypatch.setenv("LIFEINBOX_LLM_BASE_URL", "http://provider.test/v1")
    monkeypatch.setenv("LIFEINBOX_LLM_TIMEOUT_SECONDS", "NaN")

    with pytest.raises(EmbeddingConfigurationError) as exception_info:
        EmbeddingSettings.from_environment()

    assert "secret-test-key" not in str(exception_info.value)
