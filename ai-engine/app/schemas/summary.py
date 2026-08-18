from typing import Self

from pydantic import BaseModel, Field, field_validator, model_validator


MAX_SUMMARY_INPUT_CHARS = 20_000
MAX_SUMMARY_OUTPUT_CHARS = 2_000


class SummaryRequest(BaseModel):
    """只接收生成摘要真正需要的标题和正文，不传递完整 InboxItem。"""

    title: str | None = Field(default=None, max_length=255)
    text: str = Field(min_length=1, max_length=MAX_SUMMARY_INPUT_CHARS)

    @field_validator("title")
    @classmethod
    def normalize_title(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized or None

    @model_validator(mode="after")
    def normalize_and_validate_text(self) -> Self:
        normalized = self.text.strip()
        if not normalized:
            raise ValueError("text 不能为空或全为空白")
        self.text = normalized
        return self


class SummaryResponse(BaseModel):
    summary: str = Field(min_length=1, max_length=MAX_SUMMARY_OUTPUT_CHARS)
