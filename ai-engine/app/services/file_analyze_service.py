from fastapi import UploadFile

from app.schemas.analyze import AnalyzeRequest, AnalyzeResult, PreparedContent
from app.services.analyze_service import AnalyzeService
from app.services.document_text_extractor import DocumentTextExtractor


class FileAnalyzeService:
    """临时提取上传文档正文，再复用统一 Analyze Service 完成一次 AI 分析。"""

    def __init__(
        self,
        text_extractor: DocumentTextExtractor,
        analyze_service: AnalyzeService,
    ) -> None:
        self._text_extractor = text_extractor
        self._analyze_service = analyze_service

    def analyze(self, file: UploadFile, title: str | None) -> AnalyzeResult:
        prepared = self.prepare(file, title)
        return self._analyze_service.analyze(
            AnalyzeRequest(title=prepared.title, text=prepared.text)
        )

    def prepare(self, file: UploadFile, title: str | None) -> PreparedContent:
        """只解析受限文档，不调用 LLM，也不在 Python 保存业务数据。"""

        text = self._text_extractor.extract(
            file.filename,
            file.content_type,
            file.file,
        )
        normalized_title = title.strip() if title and title.strip() else None
        fallback_title = (file.filename or "文档")[:255]
        return PreparedContent(
            title=normalized_title or fallback_title,
            text=text,
        )
