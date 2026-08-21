from app.schemas.analyze import AnalyzeRequest, AnalyzeResult
from app.schemas.url_analyze import UrlAnalyzeRequest
from app.services.analyze_service import AnalyzeService
from app.services.url_content_extractor import UrlContentExtractor


class UrlAnalyzeService:
    """先安全提取网页正文，再复用现有 Analyze Service 完成一次 AI 分析。"""

    def __init__(
        self,
        content_extractor: UrlContentExtractor,
        analyze_service: AnalyzeService,
    ) -> None:
        self._content_extractor = content_extractor
        self._analyze_service = analyze_service

    def analyze(self, request: UrlAnalyzeRequest) -> AnalyzeResult:
        extracted = self._content_extractor.extract(request.url)
        analyze_request = AnalyzeRequest(
            title=request.title or extracted.title,
            text=extracted.text,
        )
        return self._analyze_service.analyze(analyze_request)
