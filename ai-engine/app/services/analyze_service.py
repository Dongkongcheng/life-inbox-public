from pydantic import ValidationError

from app.schemas.analyze import AnalyzeRequest, AnalyzeResult
from app.services.llm_client import LlmClient, LlmInvalidResponseError


class AnalyzeService:
    """一次 LLM 调用生成全部 Analyze 字段，并严格验证结构化结果。"""

    def __init__(self, llm_client: LlmClient) -> None:
        self._llm_client = llm_client

    def analyze(self, request: AnalyzeRequest) -> AnalyzeResult:
        raw_result = self._llm_client.generate_analysis(request.title, request.text)
        try:
            # JSON Mode 只保证语法；分类、数量和实体类型等业务契约仍由 Pydantic 验证。
            return AnalyzeResult.model_validate_json(raw_result, strict=True)
        except ValidationError as exception:
            raise LlmInvalidResponseError("LLM 分析结果不符合约定结构") from exception
