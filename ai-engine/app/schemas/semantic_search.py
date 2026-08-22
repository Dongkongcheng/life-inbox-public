import math

from pydantic import BaseModel, ConfigDict, Field, field_validator


MAX_SEMANTIC_QUERY_CHARS = 200
DEFAULT_SEMANTIC_LIMIT = 20
MAX_SEMANTIC_LIMIT = 100


class SemanticSearchRequest(BaseModel):
    """Java 只传用户查询与有界候选数，不把业务过滤条件复制进 Qdrant。"""

    model_config = ConfigDict(extra="forbid")

    query: str = Field(min_length=1, max_length=MAX_SEMANTIC_QUERY_CHARS)
    limit: int = Field(default=DEFAULT_SEMANTIC_LIMIT, ge=1, le=MAX_SEMANTIC_LIMIT)

    @field_validator("query")
    @classmethod
    def validate_query(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("query 不能为空或全为空白")
        return normalized


class SemanticSearchCandidate(BaseModel):
    """Qdrant 只返回候选 ID 与瞬时相似度，最终业务数据仍由 Java/MySQL 解析。"""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    inbox_item_id: int = Field(alias="inboxItemId", gt=0)
    score: float

    @field_validator("score")
    @classmethod
    def validate_score(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("score 必须是有限数字")
        return value


class SemanticSearchResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    results: list[SemanticSearchCandidate]
