import math

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


MAX_RERANK_QUERY_CHARS = 200
MAX_RERANK_DOCUMENTS = 100
MAX_RERANK_DOCUMENT_CHARS = 2_000
DEFAULT_RERANK_TOP_K = 20


class RerankDocument(BaseModel):
    """仅携带稳定 InboxItem ID 与有界相关性文本，不复制业务对象。"""

    model_config = ConfigDict(extra="forbid")

    id: int = Field(gt=0)
    text: str = Field(min_length=1, max_length=MAX_RERANK_DOCUMENT_CHARS)

    @field_validator("text")
    @classmethod
    def validate_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("document text 不能为空或全为空白")
        return normalized


class RerankRequest(BaseModel):
    """Rerank 只精排 Retrieval 已产生的有限候选，不负责重新召回。"""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    query: str = Field(min_length=1, max_length=MAX_RERANK_QUERY_CHARS)
    documents: list[RerankDocument] = Field(max_length=MAX_RERANK_DOCUMENTS)
    top_k: int = Field(
        default=DEFAULT_RERANK_TOP_K,
        alias="topK",
        ge=1,
        le=MAX_RERANK_DOCUMENTS,
    )

    @field_validator("query")
    @classmethod
    def validate_query(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("query 不能为空或全为空白")
        return normalized

    @model_validator(mode="after")
    def validate_document_ids(self) -> "RerankRequest":
        ids = [document.id for document in self.documents]
        if len(ids) != len(set(ids)):
            raise ValueError("document id 不能重复")
        return self


class RerankCandidate(BaseModel):
    """Score 仅用于本次排序，Java 会按 ID 映射回现有权威候选。"""

    model_config = ConfigDict(extra="forbid")

    id: int = Field(gt=0)
    score: float

    @field_validator("score")
    @classmethod
    def validate_score(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("score 必须是有限数字")
        return value


class RerankResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    results: list[RerankCandidate]
