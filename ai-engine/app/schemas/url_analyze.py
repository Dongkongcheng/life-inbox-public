from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator


MAX_URL_CHARS = 1_000

UrlText = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=1,
        max_length=MAX_URL_CHARS,
        strict=True,
    ),
]


class UrlAnalyzeRequest(BaseModel):
    """URL Analyze 只接收网页地址和可选的 InboxItem 标题。"""

    model_config = ConfigDict(extra="forbid")

    url: UrlText
    title: str | None = Field(default=None, max_length=255)

    @field_validator("title")
    @classmethod
    def normalize_title(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized or None
