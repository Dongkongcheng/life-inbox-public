from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator


MAX_ANALYZE_INPUT_CHARS = 20_000
MAX_ANALYZE_SUMMARY_CHARS = 2_000
MAX_ANALYZE_TAGS = 5
MAX_ANALYZE_TAG_CHARS = 64

ALLOWED_CATEGORIES = (
    "技术学习",
    "学习成长",
    "工作",
    "求职",
    "生活",
    "财务",
    "想法",
    "资讯",
    "其他",
)

AnalyzeCategory = Literal[
    "技术学习",
    "学习成长",
    "工作",
    "求职",
    "生活",
    "财务",
    "想法",
    "资讯",
    "其他",
]

SummaryText = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=1,
        max_length=MAX_ANALYZE_SUMMARY_CHARS,
        strict=True,
    ),
]
TagText = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=1,
        max_length=MAX_ANALYZE_TAG_CHARS,
        strict=True,
    ),
]


class AnalyzeRequest(BaseModel):
    """只接收 TEXT 分析真正需要的标题和正文，不传递完整 InboxItem。"""

    model_config = ConfigDict(extra="forbid")

    title: str | None = Field(default=None, max_length=255)
    text: str = Field(min_length=1, max_length=MAX_ANALYZE_INPUT_CHARS)

    @field_validator("title")
    @classmethod
    def normalize_title(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        return normalized or None

    @field_validator("text")
    @classmethod
    def normalize_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("text 不能为空或全为空白")
        return normalized


class AnalyzeResult(BaseModel):
    """LLM 输出必须完整符合这个结构，Python 才会把结果交给 Java。"""

    model_config = ConfigDict(extra="forbid")

    summary: SummaryText
    category: AnalyzeCategory
    tags: list[TagText] = Field(min_length=1, max_length=MAX_ANALYZE_TAGS)

    @field_validator("tags")
    @classmethod
    def reject_duplicate_tags(cls, tags: list[str]) -> list[str]:
        # 英文标签按大小写不敏感比较，保留模型返回的原始展示形式。
        normalized_names = [tag.casefold() for tag in tags]
        if len(set(normalized_names)) != len(normalized_names):
            raise ValueError("tags 不能包含重复标签")
        return tags
