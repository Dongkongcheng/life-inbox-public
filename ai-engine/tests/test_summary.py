import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import LlmConfigurationError, LlmSettings
from app.main import app, get_summary_service
from app.schemas.summary import SummaryResponse
from app.services.llm_client import (
    LlmClient,
    LlmInvalidResponseError,
    LlmServiceError,
    LlmTimeoutError,
)


client = TestClient(app)


class SuccessfulSummaryService:
    def summarize(self, request) -> SummaryResponse:
        return SummaryResponse(summary=f"摘要：{request.text}")


class FailedSummaryService:
    def summarize(self, request) -> SummaryResponse:
        raise LlmServiceError("mock provider unavailable")


class TimedOutSummaryService:
    def summarize(self, request) -> SummaryResponse:
        raise LlmTimeoutError("mock provider timeout")


@pytest.fixture(autouse=True)
def clear_dependency_overrides():
    yield
    app.dependency_overrides.clear()


@pytest.mark.parametrize("text", ["", "   "])
def test_summarize_rejects_empty_text(text: str) -> None:
    response = client.post("/summarize", json={"text": text})

    assert response.status_code == 422


def test_summarize_rejects_text_over_character_limit() -> None:
    response = client.post("/summarize", json={"text": "x" * 20_001})

    assert response.status_code == 422


def test_summarize_returns_structured_summary_with_fake_llm() -> None:
    app.dependency_overrides[get_summary_service] = lambda: SuccessfulSummaryService()

    response = client.post("/summarize", json={"title": "学习", "text": "Spring AI 入门"})

    assert response.status_code == 200
    assert response.json() == {"summary": "摘要：Spring AI 入门"}


def test_summarize_maps_llm_failure_to_service_unavailable() -> None:
    app.dependency_overrides[get_summary_service] = lambda: FailedSummaryService()

    response = client.post("/summarize", json={"text": "需要摘要的正文"})

    assert response.status_code == 503
    assert response.json() == {"detail": "LLM 服务暂不可用"}


def test_summarize_maps_llm_timeout_to_gateway_timeout() -> None:
    app.dependency_overrides[get_summary_service] = lambda: TimedOutSummaryService()

    response = client.post("/summarize", json={"text": "需要摘要的正文"})

    assert response.status_code == 504
    assert response.json() == {"detail": "LLM 请求超时"}


def test_missing_llm_environment_does_not_break_health(monkeypatch) -> None:
    for name in (
        "LIFEINBOX_LLM_API_KEY",
        "LIFEINBOX_LLM_MODEL",
        "LIFEINBOX_LLM_BASE_URL",
    ):
        monkeypatch.delenv(name, raising=False)

    health_response = client.get("/health")
    summary_response = client.post("/summarize", json={"text": "正文"})

    assert health_response.status_code == 200
    assert summary_response.status_code == 503
    assert summary_response.json() == {"detail": "LLM 配置不完整"}


def test_llm_settings_rejects_non_finite_timeout(monkeypatch) -> None:
    monkeypatch.setenv("LIFEINBOX_LLM_API_KEY", "test-key")
    monkeypatch.setenv("LIFEINBOX_LLM_MODEL", "test-model")
    monkeypatch.setenv("LIFEINBOX_LLM_BASE_URL", "http://llm.test/v1")
    monkeypatch.setenv("LIFEINBOX_LLM_TIMEOUT_SECONDS", "NaN")

    with pytest.raises(LlmConfigurationError):
        LlmSettings.from_environment()


def test_llm_client_sends_minimal_openai_compatible_request() -> None:
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "  结构化摘要  "}}]},
        )

    settings = LlmSettings(
        api_key="test-key",
        model="test-model",
        base_url="http://llm.test/v1",
        timeout_seconds=3,
    )
    llm_client = LlmClient(
        settings_loader=lambda: settings,
        transport=httpx.MockTransport(handler),
    )

    summary = llm_client.generate_summary("测试标题", "测试正文")

    assert summary == "结构化摘要"
    assert captured_request is not None
    assert str(captured_request.url) == "http://llm.test/v1/chat/completions"
    assert captured_request.headers["Authorization"] == "Bearer test-key"
    body = json.loads(captured_request.content)
    assert body["model"] == "test-model"
    assert body["messages"][1]["role"] == "user"
    assert "测试正文" in body["messages"][1]["content"]


def test_llm_client_rejects_empty_provider_content() -> None:
    settings = LlmSettings("test-key", "test-model", "http://llm.test/v1", 3)
    llm_client = LlmClient(
        settings_loader=lambda: settings,
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                json={"choices": [{"message": {"content": " "}}]},
            )
        ),
    )

    with pytest.raises(LlmInvalidResponseError):
        llm_client.generate_summary(None, "测试正文")


def test_llm_client_maps_http_timeout() -> None:
    settings = LlmSettings("test-key", "test-model", "http://llm.test/v1", 3)

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("mock timeout", request=request)

    llm_client = LlmClient(
        settings_loader=lambda: settings,
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(LlmTimeoutError):
        llm_client.generate_summary(None, "测试正文")


def test_llm_client_maps_non_success_status_without_exposing_body() -> None:
    settings = LlmSettings("test-key", "test-model", "http://llm.test/v1", 3)
    llm_client = LlmClient(
        settings_loader=lambda: settings,
        transport=httpx.MockTransport(
            lambda request: httpx.Response(500, json={"message": "upstream-secret"})
        ),
    )

    with pytest.raises(LlmServiceError) as exception_info:
        llm_client.generate_summary(None, "测试正文")

    assert "upstream-secret" not in str(exception_info.value)


def test_llm_client_rejects_malformed_provider_response() -> None:
    settings = LlmSettings("test-key", "test-model", "http://llm.test/v1", 3)
    llm_client = LlmClient(
        settings_loader=lambda: settings,
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, content=b"not-json")
        ),
    )

    with pytest.raises(LlmInvalidResponseError):
        llm_client.generate_summary(None, "测试正文")
