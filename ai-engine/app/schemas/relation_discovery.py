from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator


MAX_RELATION_SOURCE_TEXT_CHARS = 4_000
MAX_RELATION_CANDIDATE_TEXT_CHARS = 1_000
MAX_RELATION_CANDIDATES = 20

PositiveInboxItemId = Annotated[int, Field(strict=True, gt=0)]
SourceText = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=1,
        max_length=MAX_RELATION_SOURCE_TEXT_CHARS,
        strict=True,
    ),
]
CandidateText = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=1,
        max_length=MAX_RELATION_CANDIDATE_TEXT_CHARS,
        strict=True,
    ),
]


class RelationDiscoverySource(BaseModel):
    """Java 已完成业务校验；Python 仍按不可信边界限制 ID 和文本。"""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    inbox_item_id: PositiveInboxItemId = Field(alias="inboxItemId")
    text: SourceText


class RelationDiscoveryCandidate(BaseModel):
    """候选只携带 ID 和有界文本，不接收 Task 42 的 semanticScore。"""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    inbox_item_id: PositiveInboxItemId = Field(alias="inboxItemId")
    text: CandidateText


class RelationDiscoveryRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    source: RelationDiscoverySource
    candidates: list[RelationDiscoveryCandidate] = Field(
        max_length=MAX_RELATION_CANDIDATES
    )

    @model_validator(mode="after")
    def validate_candidate_ids(self) -> "RelationDiscoveryRequest":
        candidate_ids = [candidate.inbox_item_id for candidate in self.candidates]
        if self.source.inbox_item_id in candidate_ids:
            raise ValueError("source 不能出现在 candidates 中")
        if len(set(candidate_ids)) != len(candidate_ids):
            raise ValueError("candidate inboxItemId 不能重复")
        return self


class RelationDiscoveryResponse(BaseModel):
    """LLM 与内部接口共享最小输出，不包含评分、证据或持久化元数据。"""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    related_target_inbox_item_ids: list[PositiveInboxItemId] = Field(
        alias="relatedTargetInboxItemIds",
        max_length=MAX_RELATION_CANDIDATES,
    )

    @model_validator(mode="after")
    def reject_duplicate_ids(self) -> "RelationDiscoveryResponse":
        if len(set(self.related_target_inbox_item_ids)) != len(
            self.related_target_inbox_item_ids
        ):
            raise ValueError("relatedTargetInboxItemIds 不能重复")
        return self
