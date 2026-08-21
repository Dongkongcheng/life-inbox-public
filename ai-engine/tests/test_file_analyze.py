from io import BytesIO

import pytest
from fastapi import UploadFile
from fastapi.testclient import TestClient
from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject
from starlette.datastructures import Headers

from app.main import app, get_file_analyze_service
from app.schemas.analyze import MAX_ANALYZE_INPUT_CHARS, AnalyzeResult
from app.services.document_text_extractor import (
    MAX_FILE_ANALYZE_BYTES,
    MAX_PDF_PAGES,
    DocumentExtractionError,
    DocumentParseError,
    DocumentTextExtractor,
    DocumentTooLongError,
    FileContentEmptyError,
    FileEncodingUnsupportedError,
    FileTooLargeError,
    FileTypeUnsupportedError,
    PdfEncryptedError,
    PdfTextEmptyError,
)
from app.services.file_analyze_service import FileAnalyzeService
from app.services.llm_client import LlmServiceError


client = TestClient(app)


def successful_result() -> AnalyzeResult:
    return AnalyzeResult(
        summary="文档摘要",
        category="技术学习",
        tags=["LifeInbox"],
        keywords=["FastAPI"],
        entities=[],
    )


class RecordingAnalyzeService:
    def __init__(self, error: Exception | None = None) -> None:
        self.requests = []
        self.error = error

    def analyze(self, request) -> AnalyzeResult:
        self.requests.append(request)
        if self.error:
            raise self.error
        return successful_result()


class StaticDocumentExtractor:
    def __init__(self, text: str) -> None:
        self.text = text
        self.calls = []

    def extract(self, filename, content_type, stream) -> str:
        self.calls.append((filename, content_type))
        return self.text


class StaticFileAnalyzeService:
    def analyze(self, file: UploadFile, title: str | None) -> AnalyzeResult:
        return successful_result()


class FailedFileAnalyzeService:
    def __init__(self, error: Exception) -> None:
        self.error = error

    def analyze(self, file: UploadFile, title: str | None) -> AnalyzeResult:
        raise self.error


@pytest.fixture(autouse=True)
def clear_dependency_overrides():
    yield
    app.dependency_overrides.clear()


def upload_file(filename: str, content: bytes, content_type: str) -> UploadFile:
    return UploadFile(
        file=BytesIO(content),
        filename=filename,
        headers=Headers({"content-type": content_type}),
    )


def create_text_pdf(text: str = "LifeInbox PDF text") -> bytes:
    writer = PdfWriter()
    page = writer.add_blank_page(width=612, height=792)

    font = DictionaryObject(
        {
            NameObject("/Type"): NameObject("/Font"),
            NameObject("/Subtype"): NameObject("/Type1"),
            NameObject("/BaseFont"): NameObject("/Helvetica"),
        }
    )
    page[NameObject("/Resources")] = DictionaryObject(
        {
            NameObject("/Font"): DictionaryObject(
                {NameObject("/F1"): writer._add_object(font)}
            )
        }
    )
    content_stream = DecodedStreamObject()
    safe_text = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    content_stream.set_data(
        f"BT /F1 12 Tf 72 720 Td ({safe_text}) Tj ET".encode("ascii")
    )
    page[NameObject("/Contents")] = writer._add_object(content_stream)

    output = BytesIO()
    writer.write(output)
    return output.getvalue()


def create_blank_pdf(page_count: int = 1) -> bytes:
    writer = PdfWriter()
    for _ in range(page_count):
        writer.add_blank_page(width=612, height=792)
    output = BytesIO()
    writer.write(output)
    return output.getvalue()


def create_encrypted_pdf() -> bytes:
    writer = PdfWriter()
    writer.add_blank_page(width=612, height=792)
    writer.encrypt("secret")
    output = BytesIO()
    writer.write(output)
    return output.getvalue()


def test_extracts_utf8_txt_with_bom_and_normalizes_whitespace() -> None:
    extractor = DocumentTextExtractor()

    text = extractor.extract(
        "notes.txt",
        "text/plain; charset=utf-8",
        BytesIO("\ufeffLifeInbox   是收件箱。\r\n\r\nFastAPI\t负责 AI。".encode("utf-8")),
    )

    assert text == "LifeInbox 是收件箱。\n\nFastAPI 负责 AI。"


def test_markdown_is_read_as_plain_utf8_text() -> None:
    extractor = DocumentTextExtractor()

    text = extractor.extract(
        "README.MD",
        "text/markdown",
        BytesIO("# 标题\n\n- Java\n- Python".encode()),
    )

    assert text == "# 标题\n\n- Java\n- Python"


def test_extracts_text_layer_from_pdf() -> None:
    extractor = DocumentTextExtractor()

    text = extractor.extract(
        "architecture.pdf",
        "application/pdf",
        BytesIO(create_text_pdf()),
    )

    assert text == "LifeInbox PDF text"


@pytest.mark.parametrize("filename", ["empty.txt", "empty.md"])
def test_rejects_empty_or_whitespace_text_files(filename: str) -> None:
    extractor = DocumentTextExtractor()
    content = b"" if filename.endswith(".txt") else b" \n\t "

    with pytest.raises(FileContentEmptyError):
        extractor.extract(filename, "text/plain", BytesIO(content))


def test_rejects_non_utf8_text() -> None:
    with pytest.raises(FileEncodingUnsupportedError):
        DocumentTextExtractor().extract(
            "notes.txt",
            "text/plain",
            BytesIO(b"\xff\xfeinvalid"),
        )


def test_rejects_pdf_without_text_layer_as_possible_ocr_case() -> None:
    with pytest.raises(PdfTextEmptyError):
        DocumentTextExtractor().extract(
            "scan.pdf",
            "application/pdf",
            BytesIO(create_blank_pdf()),
        )


def test_rejects_corrupted_pdf() -> None:
    with pytest.raises(DocumentParseError):
        DocumentTextExtractor().extract(
            "broken.pdf",
            "application/pdf",
            BytesIO(b"%PDF-1.7\nnot a valid PDF"),
        )


def test_rejects_encrypted_pdf() -> None:
    with pytest.raises(PdfEncryptedError):
        DocumentTextExtractor().extract(
            "secret.pdf",
            "application/pdf",
            BytesIO(create_encrypted_pdf()),
        )


@pytest.mark.parametrize(
    ("filename", "content_type"),
    [
        ("document.docx", "application/octet-stream"),
        ("document.pdf", "text/plain"),
        ("document", "application/octet-stream"),
    ],
)
def test_rejects_unsupported_extension_or_mime(
    filename: str,
    content_type: str,
) -> None:
    with pytest.raises(FileTypeUnsupportedError):
        DocumentTextExtractor().extract(filename, content_type, BytesIO(b"content"))


def test_rejects_file_larger_than_analyze_limit() -> None:
    with pytest.raises(FileTooLargeError):
        DocumentTextExtractor().extract(
            "large.txt",
            "text/plain",
            BytesIO(b"x" * (MAX_FILE_ANALYZE_BYTES + 1)),
        )


def test_rejects_normalized_text_over_analyze_limit_without_truncation() -> None:
    with pytest.raises(DocumentTooLongError):
        DocumentTextExtractor().extract(
            "long.md",
            "text/markdown",
            BytesIO(("x" * (MAX_ANALYZE_INPUT_CHARS + 1)).encode()),
        )


def test_rejects_pdf_over_page_limit() -> None:
    with pytest.raises(DocumentTooLongError):
        DocumentTextExtractor().extract(
            "book.pdf",
            "application/pdf",
            BytesIO(create_blank_pdf(MAX_PDF_PAGES + 1)),
        )


def test_file_analyze_reuses_one_existing_analyze_call() -> None:
    extractor = StaticDocumentExtractor("提取后的文档正文")
    analyze_service = RecordingAnalyzeService()
    service = FileAnalyzeService(extractor, analyze_service)
    file = upload_file("stored.txt", b"ignored", "text/plain")

    result = service.analyze(file, "用户标题")

    assert result == successful_result()
    assert extractor.calls == [("stored.txt", "text/plain")]
    assert len(analyze_service.requests) == 1
    assert analyze_service.requests[0].title == "用户标题"
    assert analyze_service.requests[0].text == "提取后的文档正文"


def test_file_analyze_propagates_llm_failure_after_extraction() -> None:
    extractor = StaticDocumentExtractor("提取后的文档正文")
    analyze_service = RecordingAnalyzeService(LlmServiceError("mock LLM failure"))
    service = FileAnalyzeService(extractor, analyze_service)

    with pytest.raises(LlmServiceError):
        service.analyze(upload_file("stored.md", b"ignored", "text/plain"), None)

    assert len(analyze_service.requests) == 1


def test_file_analyze_endpoint_accepts_multipart_and_returns_analyze_result() -> None:
    app.dependency_overrides[get_file_analyze_service] = lambda: StaticFileAnalyzeService()

    response = client.post(
        "/analyze/file",
        files={"file": ("notes.txt", b"LifeInbox", "text/plain")},
        data={"title": "学习笔记"},
    )

    assert response.status_code == 200
    assert response.json() == {
        "summary": "文档摘要",
        "category": "技术学习",
        "tags": ["LifeInbox"],
        "keywords": ["FastAPI"],
        "entities": [],
    }


@pytest.mark.parametrize(
    ("error_type", "status_code", "code", "detail"),
    [
        (
            FileTypeUnsupportedError,
            415,
            "FILE_TYPE_UNSUPPORTED",
            "当前只支持 TXT、Markdown 和 PDF 文件分析",
        ),
        (FileTooLargeError, 413, "FILE_TOO_LARGE", "用于 AI 分析的文件不能超过 10MB"),
        (
            FileEncodingUnsupportedError,
            422,
            "FILE_ENCODING_UNSUPPORTED",
            "文本文件必须使用 UTF-8 编码",
        ),
        (FileContentEmptyError, 422, "FILE_CONTENT_EMPTY", "文档内容为空"),
        (
            PdfEncryptedError,
            422,
            "FILE_PDF_ENCRYPTED",
            "PDF 已加密，当前不支持密码保护文件",
        ),
        (
            PdfTextEmptyError,
            422,
            "FILE_PDF_NO_TEXT",
            "无法从 PDF 提取有效文本，文件可能需要 OCR",
        ),
        (
            DocumentTooLongError,
            413,
            "FILE_DOCUMENT_TOO_LONG",
            "文档过长，当前版本暂不支持分析",
        ),
        (DocumentParseError, 422, "FILE_EXTRACTION_FAILED", "文档解析失败"),
    ],
)
def test_document_errors_have_controlled_api_contract(
    error_type: type[DocumentExtractionError],
    status_code: int,
    code: str,
    detail: str,
) -> None:
    app.dependency_overrides[get_file_analyze_service] = lambda: FailedFileAnalyzeService(
        error_type()
    )

    response = client.post(
        "/analyze/file",
        files={"file": ("notes.txt", b"content", "text/plain")},
    )

    assert response.status_code == status_code
    assert response.json() == {"code": code, "detail": detail}


def test_file_endpoint_keeps_llm_failure_generic() -> None:
    app.dependency_overrides[get_file_analyze_service] = lambda: FailedFileAnalyzeService(
        LlmServiceError("internal provider detail")
    )

    response = client.post(
        "/analyze/file",
        files={"file": ("notes.txt", b"content", "text/plain")},
    )

    assert response.status_code == 503
    assert response.json() == {"detail": "LLM 服务暂不可用"}
