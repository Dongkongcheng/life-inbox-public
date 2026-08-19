import json
from typing import get_args

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import LlmSettings
from app.main import app, get_analyze_service
from app.schemas.analyze import (
    ALLOWED_CATEGORIES,
    ALLOWED_ENTITY_TYPES,
    AnalyzeCategory,
    AnalyzeRequest,
    AnalyzeResult,
    Entity,
    EntityType,
)
from app.services.analyze_service import AnalyzeService
from app.services.llm_client import (
    LlmClient,
    LlmInvalidResponseError,
    LlmServiceError,
    LlmTimeoutError,
)


client = TestClient(app)


class SuccessfulAnalyzeService:
    def analyze(self, request) -> AnalyzeResult:
        return AnalyzeResult(
            summary=f"摘要：{request.text}",
            category="技术学习",
            tags=["Java", "Spring AI"],
            keywords=["ChatModel"],
            entities=[{"name": "Spring AI", "type": "TECHNOLOGY"}],
        )


class FailedAnalyzeService:
    def analyze(self, request) -> AnalyzeResult:
        raise LlmServiceError("mock provider unavailable")


class InvalidAnalyzeService:
    def analyze(self, request) -> AnalyzeResult:
        raise LlmInvalidResponseError("mock invalid result")


class TimedOutAnalyzeService:
    def analyze(self, request) -> AnalyzeResult:
        raise LlmTimeoutError("mock provider timeout")


class StaticLlmClient:
    def __init__(self, content: str) -> None:
        self._content = content

    def generate_analysis(self, title: str | None, text: str) -> str:
        return self._content


def valid_result(**overrides) -> dict:
    result = {
        "summary": "结构化摘要",
        "category": "技术学习",
        "tags": ["Java"],
        "keywords": ["ChatModel"],
        "entities": [{"name": "Spring AI", "type": "TECHNOLOGY"}],
    }
    result.update(overrides)
    return result


@pytest.fixture(autouse=True)
def clear_dependency_overrides():
    yield
    app.dependency_overrides.clear()


@pytest.mark.parametrize("text", ["", "   "])
def test_analyze_rejects_empty_text(text: str) -> None:
    response = client.post("/analyze", json={"text": text})

    assert response.status_code == 422


def test_analyze_rejects_text_over_character_limit() -> None:
    response = client.post("/analyze", json={"text": "x" * 20_001})

    assert response.status_code == 422


def test_analyze_returns_structured_result() -> None:
    app.dependency_overrides[get_analyze_service] = lambda: SuccessfulAnalyzeService()

    response = client.post("/analyze", json={"title": "学习", "text": "Spring AI 入门"})

    assert response.status_code == 200
    assert response.json() == {
        "summary": "摘要：Spring AI 入门",
        "category": "技术学习",
        "tags": ["Java", "Spring AI"],
        "keywords": ["ChatModel"],
        "entities": [{"name": "Spring AI", "type": "TECHNOLOGY"}],
    }


def test_analyze_maps_llm_failure_to_service_unavailable() -> None:
    app.dependency_overrides[get_analyze_service] = lambda: FailedAnalyzeService()

    response = client.post("/analyze", json={"text": "需要分析的正文"})

    assert response.status_code == 503
    assert response.json() == {"detail": "LLM 服务暂不可用"}


def test_analyze_maps_invalid_llm_result_to_bad_gateway() -> None:
    app.dependency_overrides[get_analyze_service] = lambda: InvalidAnalyzeService()

    response = client.post("/analyze", json={"text": "需要分析的正文"})

    assert response.status_code == 502
    assert response.json() == {"detail": "LLM 返回的分析结果无效"}


def test_analyze_maps_llm_timeout_to_gateway_timeout() -> None:
    app.dependency_overrides[get_analyze_service] = lambda: TimedOutAnalyzeService()

    response = client.post("/analyze", json={"text": "需要分析的正文"})

    assert response.status_code == 504
    assert response.json() == {"detail": "LLM 请求超时"}


def test_analyze_service_parses_and_normalizes_valid_json() -> None:
    raw_result = json.dumps(
        {
            "summary": "  一段简洁摘要。  ",
            "category": "技术学习",
            "tags": ["  Java  ", "Spring AI"],
            "keywords": ["  ChatModel  "],
            "entities": [{"name": "  Spring AI  ", "type": "TECHNOLOGY"}],
        },
        ensure_ascii=False,
    )
    service = AnalyzeService(StaticLlmClient(raw_result))

    parsed = service.analyze(AnalyzeRequest(text="正文"))

    assert parsed == AnalyzeResult(
        summary="一段简洁摘要。",
        category="技术学习",
        tags=["Java", "Spring AI"],
        keywords=["ChatModel"],
        entities=[Entity(name="Spring AI", type="TECHNOLOGY")],
    )


def test_category_contract_and_prompt_use_the_same_finite_set() -> None:
    assert get_args(AnalyzeCategory) == ALLOWED_CATEGORIES
    assert get_args(EntityType) == ALLOWED_ENTITY_TYPES

    for category in ALLOWED_CATEGORIES:
        result = AnalyzeResult(
            summary="摘要",
            category=category,
            tags=["标签"],
            keywords=[],
            entities=[],
        )
        assert result.category == category


@pytest.mark.parametrize(
    "result_body",
    [
        {"summary": "摘要", "category": "编程", "tags": ["Java"]},
        {"summary": "摘要", "category": "技术学习", "tags": "Java"},
        {"summary": "摘要", "category": "技术学习", "tags": [" "]},
        {
            "summary": "摘要",
            "category": "技术学习",
            "tags": ["1", "2", "3", "4", "5", "6"],
        },
        {"summary": "摘要", "category": "技术学习", "tags": ["Java", "java"]},
        {"summary": "摘要", "category": "技术学习", "tags": [123]},
        {"summary": "摘要", "category": "技术学习", "tags": ["x" * 65]},
    ],
    ids=[
        "invalid-category",
        "tags-not-list",
        "blank-tag",
        "too-many-tags",
        "duplicate-tags",
        "tag-not-string",
        "tag-too-long",
    ],
)
def test_analyze_service_rejects_invalid_result(result_body) -> None:
    raw_result = json.dumps(valid_result(**result_body), ensure_ascii=False)
    service = AnalyzeService(StaticLlmClient(raw_result))

    with pytest.raises(LlmInvalidResponseError):
        service.analyze(AnalyzeRequest(text="正文"))


@pytest.mark.parametrize(
    "keywords",
    [
        "ChatModel",
        [123],
        ["x" * 65],
        [f"keyword-{index}" for index in range(9)],
    ],
    ids=["not-list", "not-string", "too-long", "too-many"],
)
def test_analyze_service_rejects_invalid_keywords(keywords) -> None:
    service = AnalyzeService(
        StaticLlmClient(json.dumps(valid_result(keywords=keywords), ensure_ascii=False))
    )

    with pytest.raises(LlmInvalidResponseError):
        service.analyze(AnalyzeRequest(text="正文"))


def test_keywords_are_nfkc_normalized_cleaned_and_deduplicated() -> None:
    raw_result = json.dumps(
        valid_result(
            keywords=["  OpenAI  ", "openai", "ＡＩ　开发", "AI 开发", " \t "],
        ),
        ensure_ascii=False,
    )
    service = AnalyzeService(StaticLlmClient(raw_result))

    result = service.analyze(AnalyzeRequest(text="正文"))

    assert result.keywords == ["OpenAI", "AI 开发"]


@pytest.mark.parametrize(
    "entities",
    [
        "OpenAI",
        [{"name": " ", "type": "ORGANIZATION"}],
        [{"name": "x" * 129, "type": "ORGANIZATION"}],
        [{"name": "OpenAI", "type": "COMPANY"}],
        [{"name": "OpenAI"}],
        [{"name": "OpenAI", "type": "ORGANIZATION", "relation": "发布"}],
        [
            {"name": f"Entity-{index}", "type": "OTHER"}
            for index in range(11)
        ],
    ],
    ids=[
        "not-list",
        "blank-name",
        "name-too-long",
        "invalid-type",
        "missing-type",
        "extra-field",
        "too-many",
    ],
)
def test_analyze_service_rejects_invalid_entities(entities) -> None:
    service = AnalyzeService(
        StaticLlmClient(json.dumps(valid_result(entities=entities), ensure_ascii=False))
    )

    with pytest.raises(LlmInvalidResponseError):
        service.analyze(AnalyzeRequest(text="正文"))


def test_entities_are_deduplicated_by_normalized_name_and_type() -> None:
    raw_result = json.dumps(
        valid_result(
            entities=[
                {"name": "  OpenAI  ", "type": "ORGANIZATION"},
                {"name": "openai", "type": "ORGANIZATION"},
                {"name": "OpenAI", "type": "PRODUCT"},
            ],
        ),
        ensure_ascii=False,
    )
    service = AnalyzeService(StaticLlmClient(raw_result))

    result = service.analyze(AnalyzeRequest(text="正文"))

    assert result.entities == [
        Entity(name="OpenAI", type="ORGANIZATION"),
        Entity(name="OpenAI", type="PRODUCT"),
    ]


def test_entity_names_normalize_full_width_characters_and_internal_whitespace() -> None:
    raw_result = json.dumps(
        valid_result(
            entities=[
                {"name": "  ＯｐｅｎＡＩ　研究室  ", "type": "ORGANIZATION"},
                {"name": "openai\t  研究室", "type": "ORGANIZATION"},
                {"name": "OpenAI 研究室", "type": "PRODUCT"},
            ],
        ),
        ensure_ascii=False,
    )
    service = AnalyzeService(StaticLlmClient(raw_result))

    result = service.analyze(AnalyzeRequest(text="正文"))

    assert result.entities == [
        Entity(name="OpenAI 研究室", type="ORGANIZATION"),
        Entity(name="OpenAI 研究室", type="PRODUCT"),
    ]


def test_keywords_and_entities_allow_explicit_empty_lists() -> None:
    service = AnalyzeService(
        StaticLlmClient(
            json.dumps(valid_result(keywords=[], entities=[]), ensure_ascii=False)
        )
    )

    result = service.analyze(AnalyzeRequest(text="很短的内容"))

    assert result.keywords == []
    assert result.entities == []


@pytest.mark.parametrize("missing_field", ["keywords", "entities"])
def test_analyze_service_requires_keywords_and_entities(missing_field: str) -> None:
    result_body = valid_result()
    result_body.pop(missing_field)
    service = AnalyzeService(
        StaticLlmClient(json.dumps(result_body, ensure_ascii=False))
    )

    with pytest.raises(LlmInvalidResponseError):
        service.analyze(AnalyzeRequest(text="正文"))


def test_analyze_service_rejects_non_json_and_extra_fields() -> None:
    non_json_service = AnalyzeService(StaticLlmClient("```json\n{}\n```"))
    extra_field_service = AnalyzeService(
        StaticLlmClient(
            json.dumps(
                valid_result(relations=["不应提前实现"]),
                ensure_ascii=False,
            )
        )
    )
    request = AnalyzeRequest(text="正文")

    with pytest.raises(LlmInvalidResponseError):
        non_json_service.analyze(request)
    with pytest.raises(LlmInvalidResponseError):
        extra_field_service.analyze(request)


def test_llm_client_sends_qwen_compatible_json_mode_request() -> None:
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                valid_result(),
                                ensure_ascii=False,
                            )
                        }
                    }
                ]
            },
        )

    settings = LlmSettings(
        api_key="test-key",
        model="qwen3.7-plus",
        base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
        timeout_seconds=3,
    )
    llm_client = LlmClient(
        settings_loader=lambda: settings,
        transport=httpx.MockTransport(handler),
    )

    raw_result = llm_client.generate_analysis("测试标题", "测试正文")

    assert json.loads(raw_result)["summary"] == "结构化摘要"
    assert captured_request is not None
    assert str(captured_request.url) == (
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    )
    assert captured_request.headers["Authorization"] == "Bearer test-key"
    body = json.loads(captured_request.content)
    assert body["model"] == "qwen3.7-plus"
    assert body["enable_thinking"] is False
    assert body["response_format"] == {"type": "json_object"}
    assert "max_tokens" not in body
    assert "JSON" in body["messages"][0]["content"]
    assert "测试正文" in body["messages"][1]["content"]
    for category in ALLOWED_CATEGORIES:
        assert category in body["messages"][0]["content"]
    for entity_type in ALLOWED_ENTITY_TYPES:
        assert entity_type in body["messages"][0]["content"]
    assert "keywords" in body["messages"][0]["content"]
    assert "entities" in body["messages"][0]["content"]


def test_llm_client_maps_http_timeout() -> None:
    settings = LlmSettings("test-key", "test-model", "http://llm.test/v1", 3)

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("mock timeout", request=request)

    llm_client = LlmClient(
        settings_loader=lambda: settings,
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(LlmTimeoutError):
        llm_client.generate_analysis(None, "测试正文")


def test_llm_client_maps_non_success_status_without_exposing_body() -> None:
    settings = LlmSettings("test-key", "test-model", "http://llm.test/v1", 3)
    llm_client = LlmClient(
        settings_loader=lambda: settings,
        transport=httpx.MockTransport(
            lambda request: httpx.Response(500, json={"message": "upstream-secret"})
        ),
    )

    with pytest.raises(LlmServiceError) as exception_info:
        llm_client.generate_analysis(None, "测试正文")

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
        llm_client.generate_analysis(None, "测试正文")


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
        llm_client.generate_analysis(None, "测试正文")
