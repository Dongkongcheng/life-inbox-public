import json
from datetime import date
from typing import get_args

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import LlmSettings
from app.main import app, get_action_extractor_service
from app.schemas.action import (
    ALLOWED_ACTION_TYPES,
    MAX_ACTIONS_PER_EXTRACTION,
    MAX_ACTION_EXTRACTION_INPUT_CHARS,
    ActionCandidate,
    ActionExtractionRequest,
    ActionExtractionResult,
    ActionType,
)
from app.services.action_extractor_service import ActionExtractorService
from app.services.llm_client import (
    LlmClient,
    LlmInvalidResponseError,
    LlmServiceError,
    LlmTimeoutError,
)


client = TestClient(app)


class StaticActionLlmClient:
    def __init__(self, content: str) -> None:
        self._content = content

    def generate_action_extraction(
        self,
        text: str,
        reference_date: date | None = None,
    ) -> str:
        return self._content


class SuccessfulActionExtractorService:
    def extract(self, request: ActionExtractionRequest) -> ActionExtractionResult:
        return ActionExtractionResult.from_actions(
            [
                ActionCandidate(
                    action_type="TODO",
                    title="整理 Java 面试题",
                    deadline_text=None,
                    deadline=None,
                    evidence=request.text,
                )
            ]
        )


class FailedActionExtractorService:
    def __init__(self, exception: Exception) -> None:
        self._exception = exception

    def extract(self, request: ActionExtractionRequest) -> ActionExtractionResult:
        raise self._exception


def action_candidate(**overrides) -> dict:
    candidate = {
        "actionType": "TODO",
        "title": "整理 Java 面试题",
        "deadlineText": None,
        "deadline": None,
        "evidence": "记得整理一下本周的 Java 面试题",
    }
    candidate.update(overrides)
    return candidate


def extract_from_payload(
    text: str,
    actions: list[dict],
    reference_date: date | None = None,
) -> ActionExtractionResult:
    service = ActionExtractorService(
        StaticActionLlmClient(
            json.dumps({"actions": actions}, ensure_ascii=False),
        )
    )
    return service.extract(
        ActionExtractionRequest(text=text, reference_date=reference_date)
    )


@pytest.fixture(autouse=True)
def clear_dependency_overrides():
    yield
    app.dependency_overrides.clear()


@pytest.mark.parametrize(
    ("body", "content"),
    [
        ({}, None),
        ({"text": ""}, None),
        ({"text": "   "}, None),
        ({"text": "x" * (MAX_ACTION_EXTRACTION_INPUT_CHARS + 1)}, None),
        ({"text": "正文", "referenceDate": "2026-02-31"}, None),
        (None, b"not-json"),
    ],
    ids=[
        "missing",
        "empty",
        "whitespace",
        "too-long",
        "invalid-reference-date",
        "invalid-json",
    ],
)
def test_action_endpoint_rejects_invalid_input(body, content) -> None:
    if content is not None:
        response = client.post(
            "/action/extract",
            content=content,
            headers={"Content-Type": "application/json"},
        )
    else:
        response = client.post("/action/extract", json=body)

    assert response.status_code == 422


def test_action_endpoint_returns_camel_case_structured_result() -> None:
    app.dependency_overrides[get_action_extractor_service] = (
        lambda: SuccessfulActionExtractorService()
    )

    response = client.post(
        "/action/extract",
        json={
            "text": "记得整理一下本周的 Java 面试题。",
            "referenceDate": "2026-08-24",
        },
    )

    assert response.status_code == 200
    assert response.json() == {
        "hasAction": True,
        "actions": [
            {
                "actionType": "TODO",
                "title": "整理 Java 面试题",
                "deadlineText": None,
                "deadline": None,
                "evidence": "记得整理一下本周的 Java 面试题。",
            }
        ],
    }


@pytest.mark.parametrize(
    ("exception", "expected_status", "expected_detail"),
    [
        (LlmServiceError("provider detail"), 503, "LLM 服务暂不可用"),
        (LlmTimeoutError("provider detail"), 504, "LLM 请求超时"),
        (
            LlmInvalidResponseError("provider detail"),
            502,
            "LLM 返回的分析结果无效",
        ),
    ],
)
def test_action_endpoint_uses_existing_safe_llm_error_mapping(
    exception: Exception,
    expected_status: int,
    expected_detail: str,
) -> None:
    app.dependency_overrides[get_action_extractor_service] = lambda: (
        FailedActionExtractorService(exception)
    )

    response = client.post("/action/extract", json={"text": "需要处理的正文"})

    assert response.status_code == expected_status
    assert response.json() == {"detail": expected_detail}
    assert "provider detail" not in response.text


def test_no_action_is_a_successful_first_class_result() -> None:
    result = extract_from_payload(
        "Redis 的 Lua 脚本可以保证多个操作原子执行。",
        [],
    )

    assert result.has_action is False
    assert result.actions == []


def test_simple_todo_without_deadline() -> None:
    source = "记得整理一下本周的 Java 面试题。"
    result = extract_from_payload(
        source,
        [action_candidate(evidence="记得整理一下本周的 Java 面试题")],
    )

    assert result.has_action is True
    assert len(result.actions) == 1
    assert result.actions[0].action_type == "TODO"
    assert result.actions[0].deadline_text is None
    assert result.actions[0].deadline is None


def test_explicit_full_chinese_deadline_is_normalized_safely() -> None:
    source = "软件工程课程设计报告需要在2026年8月25日前提交。"
    result = extract_from_payload(
        source,
        [
            action_candidate(
                actionType="DEADLINE",
                title="提交软件工程课程设计报告",
                deadlineText="2026年8月25日前",
                deadline=None,
                evidence="软件工程课程设计报告需要在2026年8月25日前提交",
            )
        ],
    )

    assert result.actions[0].deadline == "2026-08-25"


def test_missing_year_preserves_expression_and_discards_provider_guess() -> None:
    source = "软件工程课程设计报告需要在8月25日前提交。"
    result = extract_from_payload(
        source,
        [
            action_candidate(
                actionType="DEADLINE",
                title="提交软件工程课程设计报告",
                deadlineText="8月25日前",
                deadline="2026-08-25",
                evidence="软件工程课程设计报告需要在8月25日前提交",
            )
        ],
        reference_date=date(2026, 8, 24),
    )

    assert result.actions[0].deadline_text == "8月25日前"
    assert result.actions[0].deadline is None


def test_relative_deadline_preserves_expression_without_runtime_date() -> None:
    source = "下周五之前提交实习材料。"
    result = extract_from_payload(
        source,
        [
            action_candidate(
                actionType="DEADLINE",
                title="提交实习材料",
                deadlineText="下周五之前",
                deadline="2026-08-28",
                evidence="下周五之前提交实习材料",
            )
        ],
    )

    assert result.actions[0].deadline_text == "下周五之前"
    assert result.actions[0].deadline is None


def test_relative_deadline_is_normalized_from_explicit_reference_date() -> None:
    source = "明天之前提交软件工程课程设计报告。"
    result = extract_from_payload(
        source,
        [
            action_candidate(
                actionType="DEADLINE",
                title="提交软件工程课程设计报告",
                deadlineText="明天之前",
                deadline=None,
                evidence="明天之前提交软件工程课程设计报告",
            )
        ],
        reference_date=date(2026, 8, 24),
    )

    assert result.actions[0].action_type == "DEADLINE"
    assert result.actions[0].deadline_text == "明天之前"
    assert result.actions[0].deadline == "2026-08-25"


def test_invalid_explicit_date_keeps_deadline_candidate_unresolved() -> None:
    source = "2026年2月31日前提交报告。"
    result = extract_from_payload(
        source,
        [
            action_candidate(
                actionType="DEADLINE",
                title="提交报告",
                deadlineText="2026年2月31日前",
                deadline=None,
                evidence="2026年2月31日前提交报告",
            )
        ],
        reference_date=date(2026, 2, 1),
    )

    assert result.actions[0].deadline_text == "2026年2月31日前"
    assert result.actions[0].deadline is None


def test_publication_date_is_not_forced_into_an_action() -> None:
    result = extract_from_payload(
        "这篇文章发表于2026年8月20日，介绍了 Spring Boot 新特性。",
        [],
    )

    assert result.has_action is False


def test_opportunity_with_deadline_can_be_an_action() -> None:
    source = "Java 开发实习生，网申截止时间为2026年9月10日。"
    result = extract_from_payload(
        source,
        [
            action_candidate(
                actionType="DEADLINE",
                title="申请 Java 开发实习生",
                deadlineText="网申截止时间为2026年9月10日",
                deadline="2026-09-10",
                evidence="Java 开发实习生，网申截止时间为2026年9月10日",
            )
        ],
    )

    assert result.actions[0].title == "申请 Java 开发实习生"
    assert result.actions[0].deadline == "2026-09-10"


def test_multiple_actions_are_preserved() -> None:
    source = "2026年8月25日前提交课程设计报告，2026年8月28日前完成答辩PPT。"
    result = extract_from_payload(
        source,
        [
            action_candidate(
                actionType="DEADLINE",
                title="提交课程设计报告",
                deadlineText="2026年8月25日前",
                deadline="2026-08-25",
                evidence="2026年8月25日前提交课程设计报告",
            ),
            action_candidate(
                actionType="DEADLINE",
                title="完成答辩 PPT",
                deadlineText="2026年8月28日前",
                deadline="2026-08-28",
                evidence="2026年8月28日前完成答辩PPT",
            ),
        ],
    )

    assert result.has_action is True
    assert [action.deadline for action in result.actions] == [
        "2026-08-25",
        "2026-08-28",
    ]


@pytest.mark.parametrize(
    "candidate",
    [
        action_candidate(actionType="MAGIC_ACTION"),
        action_candidate(
            actionType="DEADLINE",
            deadlineText="2026年2月31日前",
            deadline="2026-02-31",
            evidence="2026年2月31日前提交报告",
        ),
        action_candidate(title="x" * 201),
        action_candidate(extraField="不允许"),
    ],
    ids=["unknown-action-type", "invalid-deadline", "title-too-long", "extra-field"],
)
def test_invalid_candidate_is_a_controlled_structured_output_failure(
    candidate: dict,
) -> None:
    service = ActionExtractorService(
        StaticActionLlmClient(
            json.dumps({"actions": [candidate]}, ensure_ascii=False),
        )
    )

    with pytest.raises(LlmInvalidResponseError):
        service.extract(ActionExtractionRequest(text="2026年2月31日前提交报告"))


def test_malformed_provider_json_is_a_controlled_failure() -> None:
    service = ActionExtractorService(StaticActionLlmClient("```json\n{}\n```"))

    with pytest.raises(LlmInvalidResponseError):
        service.extract(ActionExtractionRequest(text="需要处理的正文"))


def test_provider_cannot_supply_has_action_or_unbounded_candidates() -> None:
    too_many = [action_candidate() for _ in range(MAX_ACTIONS_PER_EXTRACTION + 1)]
    with_has_action = StaticActionLlmClient(
        json.dumps({"hasAction": False, "actions": []}, ensure_ascii=False)
    )
    too_many_actions = StaticActionLlmClient(
        json.dumps({"actions": too_many}, ensure_ascii=False)
    )

    for fake_client in (with_has_action, too_many_actions):
        service = ActionExtractorService(fake_client)
        with pytest.raises(LlmInvalidResponseError):
            service.extract(
                ActionExtractionRequest(text="记得整理一下本周的 Java 面试题")
            )


@pytest.mark.parametrize(
    "candidate",
    [
        action_candidate(evidence="模型捏造的证据"),
        action_candidate(
            actionType="DEADLINE",
            title="提交报告",
            deadlineText="2026年8月25日前",
            deadline="2026-08-25",
            evidence="提交报告",
        ),
        action_candidate(
            actionType="DEADLINE",
            title="提交报告",
            deadlineText="2026年8月25日前",
            deadline="2026-08-26",
            evidence="2026年8月25日前提交报告",
        ),
        action_candidate(deadlineText="明天", deadline=None),
    ],
    ids=[
        "fabricated-evidence",
        "evidence-misses-deadline",
        "normalized-date-mismatch",
        "todo-with-deadline",
    ],
)
def test_source_and_deadline_consistency_is_enforced(candidate: dict) -> None:
    service = ActionExtractorService(
        StaticActionLlmClient(
            json.dumps({"actions": [candidate]}, ensure_ascii=False),
        )
    )

    with pytest.raises(LlmInvalidResponseError):
        service.extract(
            ActionExtractionRequest(text="2026年8月25日前提交报告，明天继续整理")
        )


def test_action_type_contract_and_prompt_use_the_same_finite_set() -> None:
    assert get_args(ActionType) == ALLOWED_ACTION_TYPES


def test_action_llm_client_reuses_provider_and_json_mode() -> None:
    captured_request: httpx.Request | None = None

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"actions": []}'}}]})

    settings = LlmSettings(
        api_key="test-key",
        model="qwen-test",
        base_url="https://llm.test/v1",
        timeout_seconds=3,
    )
    llm_client = LlmClient(
        settings_loader=lambda: settings,
        transport=httpx.MockTransport(handler),
    )

    raw_result = llm_client.generate_action_extraction(
        "Redis 是内存数据库。",
        date(2026, 8, 24),
    )

    assert json.loads(raw_result) == {"actions": []}
    assert captured_request is not None
    body = json.loads(captured_request.content)
    assert str(captured_request.url) == "https://llm.test/v1/chat/completions"
    assert captured_request.headers["Authorization"] == "Bearer test-key"
    assert body["model"] == "qwen-test"
    assert body["enable_thinking"] is False
    assert body["response_format"] == {"type": "json_object"}
    assert "返回零个" not in body["messages"][0]["content"]
    assert "没有行动时" in body["messages"][0]["content"]
    assert "不要创造年份" in body["messages"][0]["content"]
    assert "相对日期由应用" in body["messages"][0]["content"]
    assert "2026-08-24" in body["messages"][1]["content"]
    assert "Redis 是内存数据库" in body["messages"][1]["content"]
    for action_type in ALLOWED_ACTION_TYPES:
        assert action_type in body["messages"][0]["content"]


def test_action_llm_client_maps_timeout() -> None:
    settings = LlmSettings("test-key", "test-model", "http://llm.test/v1", 3)

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("mock timeout", request=request)

    llm_client = LlmClient(
        settings_loader=lambda: settings,
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(LlmTimeoutError):
        llm_client.generate_action_extraction("需要处理的正文")


@pytest.mark.parametrize("status_code", [429, 500])
def test_action_llm_client_maps_provider_http_errors(status_code: int) -> None:
    settings = LlmSettings("test-key", "test-model", "http://llm.test/v1", 3)
    llm_client = LlmClient(
        settings_loader=lambda: settings,
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                status_code,
                json={"message": "upstream-secret"},
            )
        ),
    )

    with pytest.raises(LlmServiceError) as exception_info:
        llm_client.generate_action_extraction("需要处理的正文")

    assert "upstream-secret" not in str(exception_info.value)
