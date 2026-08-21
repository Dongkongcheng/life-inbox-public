import unicodedata
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator


MAX_ANALYZE_INPUT_CHARS = 20_000
MAX_ANALYZE_SUMMARY_CHARS = 2_000
MAX_ANALYZE_TAGS = 5
MAX_ANALYZE_TAG_CHARS = 64
MAX_ANALYZE_KEYWORDS = 8
MAX_ANALYZE_KEYWORD_CHARS = 64
MAX_ANALYZE_ENTITIES = 10
MAX_ANALYZE_ENTITY_NAME_CHARS = 128

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

ALLOWED_ENTITY_TYPES = (
    "PERSON",
    "ORGANIZATION",
    "LOCATION",
    "TECHNOLOGY",
    "PRODUCT",
    "EVENT",
    "OTHER",
)

EntityType = Literal[
    "PERSON",
    "ORGANIZATION",
    "LOCATION",
    "TECHNOLOGY",
    "PRODUCT",
    "EVENT",
    "OTHER",
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
KeywordText = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=MAX_ANALYZE_KEYWORD_CHARS,
        strict=True,
    ),
]
EntityName = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=1,
        max_length=MAX_ANALYZE_ENTITY_NAME_CHARS,
        strict=True,
    ),
]


class PreparedContent(BaseModel):
    """Parser/OCR 产生的统一纯文本；不包含 LLM 派生字段或 Java 业务状态。"""

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


class AnalyzeRequest(PreparedContent):
    """只接收 Analyze 真正需要的已准备标题和正文，不传递完整 InboxItem。"""


class Entity(BaseModel):
    """内容中明确出现的实体；有限类型避免模型任意创造分类。"""

    model_config = ConfigDict(extra="forbid")

    name: EntityName
    type: EntityType

    @field_validator("name", mode="before")
    @classmethod
    def normalize_name(cls, name):
        if not isinstance(name, str):
            # 非字符串仍交给严格的 EntityName 类型约束处理。
            return name
        # 统一全角字符，并折叠首尾及连续空白，保证后续实体去重稳定。
        return " ".join(unicodedata.normalize("NFKC", name).split())


class AnalyzeResult(BaseModel):
    """LLM 输出必须完整符合这个结构，Python 才会把结果交给 Java。"""

    model_config = ConfigDict(extra="forbid")

    summary: SummaryText
    category: AnalyzeCategory
    tags: list[TagText] = Field(min_length=1, max_length=MAX_ANALYZE_TAGS)
    keywords: list[KeywordText]
    entities: list[Entity]

    @field_validator("tags")
    @classmethod
    def reject_duplicate_tags(cls, tags: list[str]) -> list[str]:
        # 英文标签按大小写不敏感比较，保留模型返回的原始展示形式。
        normalized_names = [tag.casefold() for tag in tags]
        if len(set(normalized_names)) != len(normalized_names):
            raise ValueError("tags 不能包含重复标签")
        return tags

    @field_validator("keywords", mode="before")
    @classmethod
    def normalize_keywords(cls, keywords):
        if not isinstance(keywords, list):
            # 非 List 交给 Pydantic 按明确字段类型返回结构错误。
            return keywords

        normalized_keywords: list[object] = []
        seen: set[str] = set()
        for keyword in keywords:
            if not isinstance(keyword, str):
                normalized_keywords.append(keyword)
                continue

            # NFKC 统一全角字符，split/join 同时清理首尾及连续空白。
            normalized = " ".join(unicodedata.normalize("NFKC", keyword).split())
            if not normalized:
                continue
            duplicate_key = normalized.casefold()
            if duplicate_key in seen:
                continue
            seen.add(duplicate_key)
            normalized_keywords.append(normalized)

        if len(normalized_keywords) > MAX_ANALYZE_KEYWORDS:
            raise ValueError(f"keywords 不能超过 {MAX_ANALYZE_KEYWORDS} 个")
        return normalized_keywords

    @field_validator("entities")
    @classmethod
    def deduplicate_entities(cls, entities: list[Entity]) -> list[Entity]:
        unique_entities: list[Entity] = []
        seen: set[tuple[str, str]] = set()
        for entity in entities:
            duplicate_key = (entity.name.casefold(), entity.type)
            if duplicate_key in seen:
                continue
            seen.add(duplicate_key)
            unique_entities.append(entity)

        if len(unique_entities) > MAX_ANALYZE_ENTITIES:
            raise ValueError(f"entities 不能超过 {MAX_ANALYZE_ENTITIES} 个")
        return unique_entities
