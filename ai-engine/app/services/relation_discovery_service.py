from pydantic import ValidationError

from app.schemas.relation_discovery import (
    RelationDiscoveryRequest,
    RelationDiscoveryResponse,
)
from app.services.llm_client import LlmClient, LlmInvalidResponseError


class RelationDiscoveryService:
    """一次批量判定 Source 与候选的关系，只返回运行时建议。"""

    def __init__(self, llm_client: LlmClient) -> None:
        self._llm_client = llm_client

    def discover(self, request: RelationDiscoveryRequest) -> RelationDiscoveryResponse:
        if not request.candidates:
            # 空候选是成功结果，不能为了获得同一个空数组而调用 Provider。
            return RelationDiscoveryResponse(related_target_inbox_item_ids=[])

        raw_result = self._llm_client.generate_relation_discovery(
            request.source,
            request.candidates,
        )
        try:
            response = RelationDiscoveryResponse.model_validate_json(
                raw_result,
                strict=True,
            )
            supplied_ids = {
                candidate.inbox_item_id for candidate in request.candidates
            }
            returned_ids = response.related_target_inbox_item_ids
            if request.source.inbox_item_id in returned_ids:
                raise ValueError("LLM 不能返回 source inboxItemId")
            if any(inbox_item_id not in supplied_ids for inbox_item_id in returned_ids):
                raise ValueError("LLM 返回了未提供的 candidate inboxItemId")
        except (ValidationError, ValueError) as exception:
            # 未知、重复或越界 ID 使整次结果失效，不能静默丢弃后继续使用。
            raise LlmInvalidResponseError(
                "LLM Relation 结果不符合约定结构"
            ) from exception

        return response
