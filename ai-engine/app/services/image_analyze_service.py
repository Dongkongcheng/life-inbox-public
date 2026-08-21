from fastapi import UploadFile

from app.schemas.analyze import AnalyzeRequest, AnalyzeResult
from app.services.analyze_service import AnalyzeService
from app.services.image_text_extractor import ImageTextExtractor


class ImageAnalyzeService:
    """临时 OCR 图片文字，再复用统一 Analyze Service 完成一次 AI 分析。"""

    def __init__(
        self,
        text_extractor: ImageTextExtractor,
        analyze_service: AnalyzeService,
    ) -> None:
        self._text_extractor = text_extractor
        self._analyze_service = analyze_service

    def analyze(self, file: UploadFile, title: str | None) -> AnalyzeResult:
        text = self._text_extractor.extract(
            file.filename,
            file.content_type,
            file.file,
        )
        normalized_title = title.strip() if title and title.strip() else None
        fallback_title = (file.filename or "图片")[:255]
        # OCR 原文仅作为本次 Analyze 输入，不在 Python 或 Java 中额外持久化。
        return self._analyze_service.analyze(
            AnalyzeRequest(title=normalized_title or fallback_title, text=text)
        )
