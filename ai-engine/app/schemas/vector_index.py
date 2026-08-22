from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.schemas.embedding import EmbeddingRequest, MAX_EMBEDDING_INPUT_CHARS


class VectorIndexRequest(BaseModel):
    """Java 只传稳定业务 ID 与 Task 24 已准备的正文。"""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    inbox_item_id: int = Field(alias="inboxItemId", gt=0)
    text: str = Field(min_length=1, max_length=MAX_EMBEDDING_INPUT_CHARS)

    @field_validator("text")
    @classmethod
    def validate_text(cls, value: str) -> str:
        # 复用 Task 25 的同一输入策略，避免索引与独立 Embedding 对正文产生不同解释。
        return EmbeddingRequest(text=value).text


class VectorIndexResult(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    inbox_item_id: int = Field(alias="inboxItemId", gt=0)
    indexed: bool
    collection: str | None = None
    model: str | None = None
    dimension: int | None = Field(default=None, gt=0)
    content_hash: str | None = Field(
        default=None,
        alias="contentHash",
        pattern=r"^[0-9a-f]{64}$",
    )

    @model_validator(mode="after")
    def validate_indexed_result(self) -> "VectorIndexResult":
        required = (
            self.collection,
            self.model,
            self.dimension,
            self.content_hash,
        )
        if self.indexed and any(value is None for value in required):
            raise ValueError("已索引结果必须包含 Collection、模型、维度和内容 Hash")
        if not self.indexed and any(value is not None for value in required):
            raise ValueError("跳过索引时不能返回不完整的向量元数据")
        return self


class VectorDeleteResult(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    inbox_item_id: int = Field(alias="inboxItemId", gt=0)
    deleted: bool
