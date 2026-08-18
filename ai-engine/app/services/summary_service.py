from app.schemas.summary import MAX_SUMMARY_OUTPUT_CHARS, SummaryRequest, SummaryResponse
from app.services.llm_client import LlmClient, LlmInvalidResponseError


class SummaryService:
    """编排摘要生成，并在返回 Java 前再次校验模型输出。"""

    def __init__(self, llm_client: LlmClient) -> None:
        self._llm_client = llm_client

    def summarize(self, request: SummaryRequest) -> SummaryResponse:
        summary = self._llm_client.generate_summary(request.title, request.text).strip()
        if not summary or len(summary) > MAX_SUMMARY_OUTPUT_CHARS:
            raise LlmInvalidResponseError("LLM 摘要为空或过长")
        return SummaryResponse(summary=summary)
