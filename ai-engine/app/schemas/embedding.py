import math

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


MAX_EMBEDDING_INPUT_CHARS = 20_000


class EmbeddingRequest(BaseModel):
    """Embedding 只接收 Task 24 已准备好的文本，不理解 InboxItem 或内容来源。"""

    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1, max_length=MAX_EMBEDDING_INPUT_CHARS)

    @field_validator("text")
    @classmethod
    def validate_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("text 不能为空或全为空白")
        return normalized


class EmbeddingResult(BaseModel):
    """返回真实模型、运行时维度和向量；Task 25 不负责保存这份派生数据。"""

    model_config = ConfigDict(extra="forbid")

    model: str = Field(min_length=1)
    dimension: int = Field(gt=0)
    embedding: list[float] = Field(min_length=1)

    @field_validator("model")
    @classmethod
    def normalize_model(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("model 不能为空")
        return normalized

    @model_validator(mode="after")
    def validate_vector(self) -> "EmbeddingResult":
        if self.dimension != len(self.embedding):
            raise ValueError("dimension 必须等于 embedding 长度")
        if any(not math.isfinite(value) for value in self.embedding):
            raise ValueError("embedding 只能包含有限数字")
        return self
