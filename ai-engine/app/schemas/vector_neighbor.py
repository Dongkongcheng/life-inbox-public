import math

from pydantic import BaseModel, ConfigDict, Field, field_validator


DEFAULT_VECTOR_NEIGHBOR_LIMIT = 20
MAX_VECTOR_NEIGHBOR_LIMIT = 20


class VectorNeighborRequest(BaseModel):
    """以已有 InboxItem Point 为来源，限制最终希望获得的候选数量。"""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    inbox_item_id: int = Field(alias="inboxItemId", gt=0)
    limit: int = Field(
        default=DEFAULT_VECTOR_NEIGHBOR_LIMIT,
        ge=1,
        le=MAX_VECTOR_NEIGHBOR_LIMIT,
    )


class VectorNeighborCandidate(BaseModel):
    """只暴露瞬时向量邻居信号，业务数据仍由 Java/MySQL 解析。"""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    inbox_item_id: int = Field(alias="inboxItemId", gt=0)
    score: float

    @field_validator("score")
    @classmethod
    def validate_score(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("score 必须是有限数字")
        return value


class VectorNeighborResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    source_indexed: bool = Field(alias="sourceIndexed")
    results: list[VectorNeighborCandidate]
