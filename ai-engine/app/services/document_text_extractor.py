import unicodedata
from io import BytesIO
from pathlib import PurePath
from typing import BinaryIO

from pypdf import PdfReader

from app.schemas.analyze import MAX_ANALYZE_INPUT_CHARS


MAX_FILE_ANALYZE_BYTES = 10 * 1024 * 1024
MAX_PDF_PAGES = 100

_SUPPORTED_EXTENSIONS = frozenset({"txt", "md", "pdf"})
_ALLOWED_CONTENT_TYPES = {
    "txt": frozenset({"", "text/plain", "application/octet-stream"}),
    "md": frozenset(
        {"", "text/markdown", "text/plain", "application/octet-stream"}
    ),
    "pdf": frozenset({"", "application/pdf", "application/octet-stream"}),
}


class DocumentExtractionError(RuntimeError):
    """文档提取的公开错误；固定 code/detail 避免暴露解析器内部信息。"""

    code = "FILE_EXTRACTION_FAILED"
    detail = "文档解析失败"
    status_code = 422

    def __init__(self) -> None:
        super().__init__(self.detail)


class FileTypeUnsupportedError(DocumentExtractionError):
    code = "FILE_TYPE_UNSUPPORTED"
    detail = "当前只支持 TXT、Markdown 和 PDF 文件分析"
    status_code = 415


class FileTooLargeError(DocumentExtractionError):
    code = "FILE_TOO_LARGE"
    detail = "用于 AI 分析的文件不能超过 10MB"
    status_code = 413


class FileEncodingUnsupportedError(DocumentExtractionError):
    code = "FILE_ENCODING_UNSUPPORTED"
    detail = "文本文件必须使用 UTF-8 编码"
    status_code = 422


class FileContentEmptyError(DocumentExtractionError):
    code = "FILE_CONTENT_EMPTY"
    detail = "文档内容为空"
    status_code = 422


class PdfEncryptedError(DocumentExtractionError):
    code = "FILE_PDF_ENCRYPTED"
    detail = "PDF 已加密，当前不支持密码保护文件"
    status_code = 422


class PdfTextEmptyError(DocumentExtractionError):
    code = "FILE_PDF_NO_TEXT"
    detail = "无法从 PDF 提取有效文本，文件可能需要 OCR"
    status_code = 422


class DocumentTooLongError(DocumentExtractionError):
    code = "FILE_DOCUMENT_TOO_LONG"
    detail = "文档过长，当前版本暂不支持分析"
    status_code = 413


class DocumentParseError(DocumentExtractionError):
    code = "FILE_EXTRACTION_FAILED"
    detail = "文档解析失败"
    status_code = 422


class DocumentTextExtractor:
    """从受限 TXT/MD/PDF 中提取文本，不负责 AI 调用或业务数据持久化。"""

    def extract(
        self,
        filename: str | None,
        content_type: str | None,
        stream: BinaryIO,
    ) -> str:
        extension = self._validate_file_type(filename, content_type)
        content = self._read_limited(stream)

        if extension in {"txt", "md"}:
            text = self._decode_utf8(content)
            empty_error: type[DocumentExtractionError] = FileContentEmptyError
        else:
            text = self._extract_pdf(content)
            empty_error = PdfTextEmptyError

        normalized = _normalize_document_text(text)
        if not normalized:
            raise empty_error()
        if len(normalized) > MAX_ANALYZE_INPUT_CHARS:
            # 当前没有 Chunking/RAG；明确失败比静默截断后生成不完整摘要更可靠。
            raise DocumentTooLongError()
        return normalized

    @staticmethod
    def _validate_file_type(filename: str | None, content_type: str | None) -> str:
        if not filename:
            raise FileTypeUnsupportedError()
        suffix = PurePath(filename).suffix.casefold()
        extension = suffix[1:] if suffix.startswith(".") else ""
        if extension not in _SUPPORTED_EXTENSIONS:
            raise FileTypeUnsupportedError()

        media_type = (content_type or "").partition(";")[0].strip().casefold()
        if media_type not in _ALLOWED_CONTENT_TYPES[extension]:
            raise FileTypeUnsupportedError()
        return extension

    @staticmethod
    def _read_limited(stream: BinaryIO) -> bytes:
        try:
            stream.seek(0)
            content = stream.read(MAX_FILE_ANALYZE_BYTES + 1)
        except (OSError, ValueError) as exception:
            raise DocumentParseError() from exception
        if len(content) > MAX_FILE_ANALYZE_BYTES:
            raise FileTooLargeError()
        if not content:
            raise FileContentEmptyError()
        return content

    @staticmethod
    def _decode_utf8(content: bytes) -> str:
        try:
            # utf-8-sig 同时兼容普通 UTF-8 和带 BOM 的 UTF-8。
            return content.decode("utf-8-sig", errors="strict")
        except UnicodeDecodeError as exception:
            raise FileEncodingUnsupportedError() from exception

    @staticmethod
    def _extract_pdf(content: bytes) -> str:
        try:
            reader = PdfReader(BytesIO(content), strict=False)
            if reader.is_encrypted:
                # 当前 API 不接收密码，也不尝试空密码；加密 PDF 留给后续独立能力。
                raise PdfEncryptedError()
            if len(reader.pages) > MAX_PDF_PAGES:
                raise DocumentTooLongError()

            pages: list[str] = []
            for page in reader.pages:
                pages.append(page.extract_text() or "")
                # 边解析边限制，避免已明显超长时继续处理剩余页面。
                if len("\n\n".join(pages)) > MAX_ANALYZE_INPUT_CHARS * 2:
                    raise DocumentTooLongError()
            return "\n\n".join(pages)
        except DocumentExtractionError:
            raise
        except Exception as exception:
            # PDF 是不可信输入；解析器内部错误统一转换为安全的公开错误。
            raise DocumentParseError() from exception


def _normalize_document_text(text: str) -> str:
    normalized = unicodedata.normalize("NFKC", text).replace("\r\n", "\n").replace(
        "\r", "\n"
    )
    lines = [" ".join(line.split()) for line in normalized.split("\n")]

    # 保留最多一个空行表达段落边界，同时折叠行内连续空白。
    result: list[str] = []
    previous_blank = True
    for line in lines:
        if line:
            result.append(line)
            previous_blank = False
        elif not previous_blank:
            result.append("")
            previous_blank = True
    return "\n".join(result).strip()
