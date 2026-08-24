import re
import unicodedata
from datetime import date

from pydantic import ValidationError

from app.schemas.action import (
    ActionCandidate,
    ActionExtractionPayload,
    ActionExtractionRequest,
    ActionExtractionResult,
)
from app.services.llm_client import LlmClient, LlmInvalidResponseError


_ISO_DATE_PATTERN = re.compile(r"(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)")
_CHINESE_DATE_PATTERN = re.compile(
    r"(?<!\d)(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日"
)


class ActionExtractorService:
    """从准备文本提取有界 Action 建议，不持久化或确认任何业务状态。"""

    def __init__(self, llm_client: LlmClient) -> None:
        self._llm_client = llm_client

    def extract(self, request: ActionExtractionRequest) -> ActionExtractionResult:
        raw_result = self._llm_client.generate_action_extraction(request.text)
        try:
            # JSON Mode 只保证语法；枚举、长度、数量和日期仍需在本层按不可信输入校验。
            payload = ActionExtractionPayload.model_validate_json(
                raw_result,
                strict=True,
            )
            actions = [
                self._validate_source_and_deadline(candidate, request.text)
                for candidate in payload.actions
            ]
        except (ValidationError, ValueError) as exception:
            raise LlmInvalidResponseError("LLM Action 结果不符合约定结构") from exception

        # hasAction 只根据最终有效列表生成，避免信任 LLM 产生互相矛盾的状态。
        return ActionExtractionResult.from_actions(actions)

    def _validate_source_and_deadline(
        self,
        candidate: ActionCandidate,
        source_text: str,
    ) -> ActionCandidate:
        if not _is_source_fragment(candidate.evidence, source_text):
            raise ValueError("evidence 必须来自输入文本")

        if candidate.deadline_text is None:
            return candidate
        if not _is_source_fragment(candidate.deadline_text, source_text):
            raise ValueError("deadlineText 必须来自输入文本")
        if not _is_source_fragment(candidate.deadline_text, candidate.evidence):
            raise ValueError("DEADLINE evidence 必须包含 deadlineText")

        explicit_dates = _extract_explicit_dates(candidate.deadline_text)
        if not explicit_dates:
            # 缺少年份或相对日期时，即使 Provider 猜出合法日期也必须丢弃该猜测。
            return candidate.model_copy(update={"deadline": None})

        if candidate.deadline is not None:
            if candidate.deadline not in explicit_dates:
                raise ValueError("deadline 与 deadlineText 中的完整日期不一致")
            return candidate

        # 只有一个完整日期时可由应用安全规范化；多个日期仍保留原文并等待后续确认。
        if len(explicit_dates) == 1:
            return candidate.model_copy(update={"deadline": next(iter(explicit_dates))})
        return candidate


def _is_source_fragment(fragment: str, source: str) -> bool:
    normalized_fragment = _normalize_source_text(fragment)
    normalized_source = _normalize_source_text(source)
    return bool(normalized_fragment) and normalized_fragment in normalized_source


def _normalize_source_text(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).split())


def _extract_explicit_dates(deadline_text: str) -> set[str]:
    normalized_dates: set[str] = set()
    for pattern in (_ISO_DATE_PATTERN, _CHINESE_DATE_PATTERN):
        for match in pattern.finditer(deadline_text):
            try:
                normalized_dates.add(
                    date(
                        int(match.group(1)),
                        int(match.group(2)),
                        int(match.group(3)),
                    ).isoformat()
                )
            except ValueError:
                # 原文可能包含无效日期；保留 deadlineText，但不能制造 normalized deadline。
                continue
    return normalized_dates
