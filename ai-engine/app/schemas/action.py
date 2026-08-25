import re
from datetime import date
from typing import Annotated, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    field_validator,
    model_validator,
)

from app.schemas.analyze import MAX_ANALYZE_INPUT_CHARS


MAX_ACTION_EXTRACTION_INPUT_CHARS = MAX_ANALYZE_INPUT_CHARS
MAX_ACTIONS_PER_EXTRACTION = 10
MAX_ACTION_TITLE_CHARS = 200
MAX_DEADLINE_TEXT_CHARS = 100
MAX_ACTION_EVIDENCE_CHARS = 500

ALLOWED_ACTION_TYPES = ("TODO", "DEADLINE")
ActionType = Literal["TODO", "DEADLINE"]

ActionTitle = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=1,
        max_length=MAX_ACTION_TITLE_CHARS,
        strict=True,
    ),
]
DeadlineText = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=1,
        max_length=MAX_DEADLINE_TEXT_CHARS,
        strict=True,
    ),
]
ActionEvidence = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=1,
        max_length=MAX_ACTION_EVIDENCE_CHARS,
        strict=True,
    ),
]
IsoCalendarDate = Annotated[
    str,
    StringConstraints(
        pattern=r"^\d{4}-\d{2}-\d{2}$",
        strict=True,
    ),
]


class ActionExtractionRequest(BaseModel):
    """接收准备文本和可选稳定参考日期，不接收 InboxItem 或业务状态。"""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    text: str = Field(min_length=1, max_length=MAX_ACTION_EXTRACTION_INPUT_CHARS)
    reference_date: date | None = Field(default=None, alias="referenceDate")

    @field_validator("text")
    @classmethod
    def normalize_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("text 不能为空或全为空白")
        return normalized


class ActionCandidate(BaseModel):
    """Action 只是 AI 建议；这里严格校验结构，但不创建或修改 Todo。"""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    action_type: ActionType = Field(alias="actionType")
    title: ActionTitle
    deadline_text: DeadlineText | None = Field(default=None, alias="deadlineText")
    deadline: IsoCalendarDate | None = None
    evidence: ActionEvidence

    @field_validator("deadline")
    @classmethod
    def validate_calendar_date(cls, value: str | None) -> str | None:
        if value is None:
            return None
        try:
            date.fromisoformat(value)
        except ValueError as exception:
            raise ValueError("deadline 必须是有效的 ISO 日历日期") from exception
        return value

    @field_validator("evidence")
    @classmethod
    def reject_html_evidence(cls, value: str) -> str:
        if re.search(r"<\s*/?\s*[A-Za-z][^>]*>", value):
            raise ValueError("evidence 必须是纯文本")
        return value

    @model_validator(mode="after")
    def validate_deadline_semantics(self) -> "ActionCandidate":
        if self.action_type == "DEADLINE" and self.deadline_text is None:
            raise ValueError("DEADLINE 必须保留 deadlineText")
        if self.action_type == "TODO" and (
            self.deadline_text is not None or self.deadline is not None
        ):
            raise ValueError("TODO 不能携带截止日期")
        return self


class ActionExtractionPayload(BaseModel):
    """LLM 只返回候选列表；hasAction 不由外部模型决定。"""

    model_config = ConfigDict(extra="forbid")

    actions: list[ActionCandidate] = Field(max_length=MAX_ACTIONS_PER_EXTRACTION)


class ActionExtractionResult(ActionExtractionPayload):
    """对外结果的 hasAction 始终由经过验证的候选列表派生。"""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    has_action: bool = Field(alias="hasAction")

    @classmethod
    def from_actions(cls, actions: list[ActionCandidate]) -> "ActionExtractionResult":
        return cls(has_action=bool(actions), actions=actions)

    @model_validator(mode="after")
    def validate_has_action(self) -> "ActionExtractionResult":
        if self.has_action != bool(self.actions):
            raise ValueError("hasAction 必须与 actions 是否为空保持一致")
        return self
