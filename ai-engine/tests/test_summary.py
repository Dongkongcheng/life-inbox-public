import pytest
from fastapi.testclient import TestClient

from app.config import LlmConfigurationError, LlmSettings
from app.main import app, get_summary_service
from app.schemas.analyze import AnalyzeResult
from app.schemas.summary import SummaryRequest, SummaryResponse
from app.services.summary_service import SummaryService


client = TestClient(app)


class SuccessfulSummaryService:
    def summarize(self, request) -> SummaryResponse:
        return SummaryResponse(summary=f"摘要：{request.text}")


class RecordingAnalyzeService:
    def __init__(self) -> None:
        self.calls = 0

    def analyze(self, request) -> AnalyzeResult:
        self.calls += 1
        return AnalyzeResult(
            summary="统一分析生成的摘要",
            category="技术学习",
            tags=["Java"],
        )


@pytest.fixture(autouse=True)
def clear_dependency_overrides():
    yield
    app.dependency_overrides.clear()


@pytest.mark.parametrize("text", ["", "   "])
def test_summarize_rejects_empty_text(text: str) -> None:
    response = client.post("/summarize", json={"text": text})

    assert response.status_code == 422


def test_summarize_keeps_legacy_response_contract() -> None:
    app.dependency_overrides[get_summary_service] = lambda: SuccessfulSummaryService()

    response = client.post("/summarize", json={"title": "学习", "text": "Spring AI 入门"})

    assert response.status_code == 200
    assert response.json() == {"summary": "摘要：Spring AI 入门"}


def test_summary_service_reuses_single_analyze_call() -> None:
    analyze_service = RecordingAnalyzeService()
    summary_service = SummaryService(analyze_service)

    response = summary_service.summarize(
        SummaryRequest(title="学习", text="Spring AI 入门")
    )

    assert response == SummaryResponse(summary="统一分析生成的摘要")
    assert analyze_service.calls == 1


def test_missing_llm_environment_does_not_break_health_or_compatibility(monkeypatch) -> None:
    for name in (
        "LIFEINBOX_LLM_API_KEY",
        "LIFEINBOX_LLM_MODEL",
        "LIFEINBOX_LLM_BASE_URL",
    ):
        monkeypatch.delenv(name, raising=False)

    health_response = client.get("/health")
    analyze_response = client.post("/analyze", json={"text": "正文"})
    summary_response = client.post("/summarize", json={"text": "正文"})

    assert health_response.status_code == 200
    assert analyze_response.status_code == 503
    assert summary_response.status_code == 503
    assert analyze_response.json() == {"detail": "LLM 配置不完整"}
    assert summary_response.json() == {"detail": "LLM 配置不完整"}


def test_llm_settings_rejects_non_finite_timeout(monkeypatch) -> None:
    monkeypatch.setenv("LIFEINBOX_LLM_API_KEY", "test-key")
    monkeypatch.setenv("LIFEINBOX_LLM_MODEL", "test-model")
    monkeypatch.setenv("LIFEINBOX_LLM_BASE_URL", "http://llm.test/v1")
    monkeypatch.setenv("LIFEINBOX_LLM_TIMEOUT_SECONDS", "NaN")

    with pytest.raises(LlmConfigurationError):
        LlmSettings.from_environment()
