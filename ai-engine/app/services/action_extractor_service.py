import unicodedata
from datetime import date

from pydantic import ValidationError

from app.schemas.action import (
    ActionCandidate,
    ActionExtractionPayload,
    ActionExtractionRequest,
    ActionExtractionResult,
)
from app.services.deadline_normalizer import DeadlineNormalizer
from app.services.llm_client import LlmClient, LlmInvalidResponseError


class ActionExtractorService:
    """从准备文本提取有界 Action 建议，不持久化或确认任何业务状态。"""

    def __init__(
        self,
        llm_client: LlmClient,
        deadline_normalizer: DeadlineNormalizer | None = None,
    ) -> None:
        self._llm_client = llm_client
        self._deadline_normalizer = deadline_normalizer or DeadlineNormalizer()

    def extract(self, request: ActionExtractionRequest) -> ActionExtractionResult:
        raw_result = self._llm_client.generate_action_extraction(
            request.text,
            request.reference_date,
        )
        try:
            # JSON Mode 只保证语法；枚举、长度、数量和日期仍需在本层按不可信输入校验。
            payload = ActionExtractionPayload.model_validate_json(
                raw_result,
                strict=True,
            )
            actions = [
                self._validate_source_and_deadline(
                    candidate,
                    request.text,
                    request.reference_date,
                )
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
        reference_date: date | None,
    ) -> ActionCandidate:
        if not _is_source_fragment(candidate.evidence, source_text):
            raise ValueError("evidence 必须来自输入文本")

        if candidate.deadline_text is None:
            return candidate
        if not _is_source_fragment(candidate.deadline_text, source_text):
            raise ValueError("deadlineText 必须来自输入文本")
        if not _is_source_fragment(candidate.deadline_text, candidate.evidence):
            raise ValueError("DEADLINE evidence 必须包含 deadlineText")

        normalized_deadline = self._deadline_normalizer.normalize(
            candidate.deadline_text,
            reference_date,
        )
        if normalized_deadline is None:
            # 无法证明的表达仍是 DEADLINE，只保留原始 deadlineText，不丢弃 Candidate。
            return candidate.model_copy(update={"deadline": None})

        normalized_iso_date = normalized_deadline.isoformat()
        if candidate.deadline is not None and candidate.deadline != normalized_iso_date:
            raise ValueError("deadline 与确定性规范化结果不一致")
        # LLM 负责识别语义；日期运算最终以纯 Normalizer 的结果为准。
        return candidate.model_copy(update={"deadline": normalized_iso_date})


def _is_source_fragment(fragment: str, source: str) -> bool:
    normalized_fragment = _normalize_source_text(fragment)
    normalized_source = _normalize_source_text(source)
    return bool(normalized_fragment) and normalized_fragment in normalized_source


def _normalize_source_text(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).split())
