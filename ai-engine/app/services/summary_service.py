from app.schemas.summary import SummaryRequest, SummaryResponse
from app.services.analyze_service import AnalyzeService


class SummaryService:
    """保留旧 /summarize 契约，真正的 LLM 处理统一委托给 Analyze。"""

    def __init__(self, analyze_service: AnalyzeService) -> None:
        self._analyze_service = analyze_service

    def summarize(self, request: SummaryRequest) -> SummaryResponse:
        result = self._analyze_service.analyze(request)
        return SummaryResponse(summary=result.summary)
