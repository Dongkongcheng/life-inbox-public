from io import BytesIO
from types import SimpleNamespace

import pytest
from fastapi import UploadFile
from fastapi.testclient import TestClient
from PIL import Image
from starlette.datastructures import Headers

import app.services.image_text_extractor as image_extractor_module
from app.main import app, get_image_analyze_service
from app.schemas.analyze import MAX_ANALYZE_INPUT_CHARS, AnalyzeResult, PreparedContent
from app.services.image_analyze_service import ImageAnalyzeService
from app.services.image_text_extractor import (
    MAX_IMAGE_ANALYZE_BYTES,
    ImageDimensionsTooLargeError,
    ImageExtractionError,
    ImageInvalidError,
    ImageOcrFailedError,
    ImageTextEmptyError,
    ImageTextExtractor,
    ImageTextTooLongError,
    ImageTooLargeError,
    ImageTypeUnsupportedError,
)
from app.services.llm_client import LlmServiceError


client = TestClient(app)


def successful_result() -> AnalyzeResult:
    return AnalyzeResult(
        summary="图片摘要",
        category="技术学习",
        tags=["OCR"],
        keywords=["RapidOCR"],
        entities=[],
    )


class FakeOcrEngine:
    def __init__(self, texts=None, error: Exception | None = None) -> None:
        self.texts = texts
        self.error = error
        self.images: list[Image.Image] = []

    def __call__(self, image: Image.Image):
        self.images.append(image.copy())
        if self.error:
            raise self.error
        return SimpleNamespace(txts=self.texts)


class RecordingAnalyzeService:
    def __init__(self, error: Exception | None = None) -> None:
        self.requests = []
        self.error = error

    def analyze(self, request) -> AnalyzeResult:
        self.requests.append(request)
        if self.error:
            raise self.error
        return successful_result()


class StaticImageExtractor:
    def __init__(self, text: str) -> None:
        self.text = text
        self.calls = []

    def extract(self, filename, content_type, stream) -> str:
        self.calls.append((filename, content_type))
        return self.text


class StaticImageAnalyzeService:
    def analyze(self, file: UploadFile, title: str | None) -> AnalyzeResult:
        return successful_result()

    def prepare(self, file: UploadFile, title: str | None) -> PreparedContent:
        return PreparedContent(title=title or file.filename, text="OCR 后的图片文字")


class FailedImageAnalyzeService:
    def __init__(self, error: Exception) -> None:
        self.error = error

    def analyze(self, file: UploadFile, title: str | None) -> AnalyzeResult:
        raise self.error

    def prepare(self, file: UploadFile, title: str | None) -> PreparedContent:
        raise self.error


@pytest.fixture(autouse=True)
def clear_dependency_overrides():
    yield
    app.dependency_overrides.clear()


def create_image(
    image_format: str,
    size: tuple[int, int] = (80, 40),
    exif_orientation: int | None = None,
) -> bytes:
    image = Image.new("RGB", size, "white")
    output = BytesIO()
    if exif_orientation is None:
        image.save(output, format=image_format)
    else:
        exif = Image.Exif()
        exif[274] = exif_orientation
        image.save(output, format=image_format, exif=exif)
    image.close()
    return output.getvalue()


def upload_file(filename: str, content: bytes, content_type: str) -> UploadFile:
    return UploadFile(
        file=BytesIO(content),
        filename=filename,
        headers=Headers({"content-type": content_type}),
    )


@pytest.mark.parametrize(
    ("filename", "content_type", "image_format"),
    [
        ("screen.png", "image/png", "PNG"),
        ("screen.jpg", "image/jpeg", "JPEG"),
        ("screen.jpeg", "image/jpeg", "JPEG"),
        ("screen.webp", "image/webp", "WEBP"),
    ],
)
def test_extracts_supported_image_formats(
    filename: str,
    content_type: str,
    image_format: str,
) -> None:
    engine = FakeOcrEngine(["LifeInbox", "OCR 测试"])
    extractor = ImageTextExtractor(lambda: engine)

    text = extractor.extract(
        filename,
        content_type,
        BytesIO(create_image(image_format)),
    )

    assert text == "LifeInbox\nOCR 测试"
    assert len(engine.images) == 1


def test_ocr_engine_is_initialized_lazily_and_reused() -> None:
    engine = FakeOcrEngine(["LifeInbox"])
    factory_calls = []

    def factory():
        factory_calls.append(True)
        return engine

    extractor = ImageTextExtractor(factory)
    assert factory_calls == []

    for _ in range(2):
        extractor.extract("screen.png", "image/png", BytesIO(create_image("PNG")))

    assert factory_calls == [True]


def test_applies_exif_orientation_before_ocr() -> None:
    engine = FakeOcrEngine(["Orientation"])
    extractor = ImageTextExtractor(lambda: engine)

    extractor.extract(
        "photo.jpg",
        "image/jpeg",
        BytesIO(create_image("JPEG", size=(80, 40), exif_orientation=6)),
    )

    assert engine.images[0].size == (40, 80)


@pytest.mark.parametrize(
    ("filename", "content_type"),
    [
        ("image.gif", "image/gif"),
        ("image.bmp", "image/bmp"),
        ("image.png", "application/octet-stream"),
        ("image", "image/png"),
    ],
)
def test_rejects_unsupported_extension_or_mime(
    filename: str,
    content_type: str,
) -> None:
    with pytest.raises(ImageTypeUnsupportedError):
        ImageTextExtractor(lambda: FakeOcrEngine(["text"])).extract(
            filename,
            content_type,
            BytesIO(b"content"),
        )


def test_rejects_non_image_content_disguised_as_png() -> None:
    with pytest.raises(ImageInvalidError):
        ImageTextExtractor(lambda: FakeOcrEngine(["text"])).extract(
            "fake.png",
            "image/png",
            BytesIO(b"not a real image"),
        )


def test_rejects_corrupted_image() -> None:
    valid_prefix = create_image("PNG")[:24]

    with pytest.raises(ImageInvalidError):
        ImageTextExtractor(lambda: FakeOcrEngine(["text"])).extract(
            "broken.png",
            "image/png",
            BytesIO(valid_prefix),
        )


def test_rejects_content_that_does_not_match_extension() -> None:
    with pytest.raises(ImageInvalidError):
        ImageTextExtractor(lambda: FakeOcrEngine(["text"])).extract(
            "wrong.png",
            "image/png",
            BytesIO(create_image("JPEG")),
        )


def test_rejects_image_larger_than_byte_limit() -> None:
    with pytest.raises(ImageTooLargeError):
        ImageTextExtractor(lambda: FakeOcrEngine(["text"])).extract(
            "large.png",
            "image/png",
            BytesIO(b"x" * (MAX_IMAGE_ANALYZE_BYTES + 1)),
        )


def test_rejects_image_side_over_limit(monkeypatch) -> None:
    monkeypatch.setattr(image_extractor_module, "MAX_IMAGE_SIDE", 20)

    with pytest.raises(ImageDimensionsTooLargeError):
        ImageTextExtractor(lambda: FakeOcrEngine(["text"])).extract(
            "wide.png",
            "image/png",
            BytesIO(create_image("PNG", size=(21, 10))),
        )


def test_rejects_image_pixel_count_over_limit(monkeypatch) -> None:
    monkeypatch.setattr(image_extractor_module, "MAX_IMAGE_PIXELS", 100)

    with pytest.raises(ImageDimensionsTooLargeError):
        ImageTextExtractor(lambda: FakeOcrEngine(["text"])).extract(
            "pixels.png",
            "image/png",
            BytesIO(create_image("PNG", size=(11, 10))),
        )


def test_normalizes_ocr_lines_and_full_width_characters() -> None:
    engine = FakeOcrEngine(["  Ｒｅｄｉｓ　Distributed   Lock ", "", "Lua\tScript"])

    text = ImageTextExtractor(lambda: engine).extract(
        "screen.png",
        "image/png",
        BytesIO(create_image("PNG")),
    )

    assert text == "Redis Distributed Lock\nLua Script"


@pytest.mark.parametrize("texts", [None, [], ["."], ["---", "1"]])
def test_rejects_empty_or_meaningless_ocr_text(texts) -> None:
    with pytest.raises(ImageTextEmptyError):
        ImageTextExtractor(lambda: FakeOcrEngine(texts)).extract(
            "empty.png",
            "image/png",
            BytesIO(create_image("PNG")),
        )


def test_rejects_ocr_text_over_analyze_limit_without_truncation() -> None:
    with pytest.raises(ImageTextTooLongError):
        ImageTextExtractor(
            lambda: FakeOcrEngine(["x" * (MAX_ANALYZE_INPUT_CHARS + 1)])
        ).extract("long.png", "image/png", BytesIO(create_image("PNG")))


def test_maps_ocr_engine_failure_to_controlled_error() -> None:
    with pytest.raises(ImageOcrFailedError):
        ImageTextExtractor(
            lambda: FakeOcrEngine(error=RuntimeError("internal model path"))
        ).extract("screen.png", "image/png", BytesIO(create_image("PNG")))


def test_image_analyze_reuses_one_existing_analyze_call() -> None:
    extractor = StaticImageExtractor("OCR 后的图片文字")
    analyze_service = RecordingAnalyzeService()
    service = ImageAnalyzeService(extractor, analyze_service)

    result = service.analyze(
        upload_file("screen.png", b"ignored", "image/png"),
        "课程通知",
    )

    assert result == successful_result()
    assert extractor.calls == [("screen.png", "image/png")]
    assert len(analyze_service.requests) == 1
    assert analyze_service.requests[0].title == "课程通知"
    assert analyze_service.requests[0].text == "OCR 后的图片文字"


def test_image_prepare_returns_ocr_text_without_calling_llm() -> None:
    extractor = StaticImageExtractor("OCR 后的图片文字")
    analyze_service = RecordingAnalyzeService()
    service = ImageAnalyzeService(extractor, analyze_service)

    prepared = service.prepare(
        upload_file("screen.png", b"ignored", "image/png"),
        None,
    )

    assert prepared == PreparedContent(title="screen.png", text="OCR 后的图片文字")
    assert analyze_service.requests == []


def test_image_analyze_propagates_llm_failure_after_ocr() -> None:
    analyze_service = RecordingAnalyzeService(LlmServiceError("mock LLM failure"))
    service = ImageAnalyzeService(
        StaticImageExtractor("OCR 后的图片文字"),
        analyze_service,
    )

    with pytest.raises(LlmServiceError):
        service.analyze(upload_file("screen.png", b"ignored", "image/png"), None)

    assert len(analyze_service.requests) == 1


def test_image_endpoint_accepts_multipart_and_returns_analyze_result() -> None:
    app.dependency_overrides[get_image_analyze_service] = (
        lambda: StaticImageAnalyzeService()
    )

    response = client.post(
        "/analyze/image",
        files={"file": ("screen.png", create_image("PNG"), "image/png")},
        data={"title": "课程通知"},
    )

    assert response.status_code == 200
    assert response.json() == {
        "summary": "图片摘要",
        "category": "技术学习",
        "tags": ["OCR"],
        "keywords": ["RapidOCR"],
        "entities": [],
    }


def test_image_prepare_endpoint_returns_plain_content() -> None:
    app.dependency_overrides[get_image_analyze_service] = (
        lambda: StaticImageAnalyzeService()
    )

    response = client.post(
        "/prepare/image",
        files={"file": ("screen.png", create_image("PNG"), "image/png")},
        data={"title": "课程通知"},
    )

    assert response.status_code == 200
    assert response.json() == {"title": "课程通知", "text": "OCR 后的图片文字"}


@pytest.mark.parametrize(
    ("error_type", "status_code", "code", "detail"),
    [
        (
            ImageTypeUnsupportedError,
            415,
            "IMAGE_TYPE_UNSUPPORTED",
            "当前只支持 JPG、PNG 和 WEBP 图片分析",
        ),
        (ImageTooLargeError, 413, "IMAGE_TOO_LARGE", "用于 AI 分析的图片不能超过 10MB"),
        (
            ImageDimensionsTooLargeError,
            413,
            "IMAGE_DIMENSIONS_TOO_LARGE",
            "图片分辨率过大，当前版本暂不支持分析",
        ),
        (ImageInvalidError, 422, "IMAGE_INVALID", "图片文件损坏或内容无效"),
        (ImageOcrFailedError, 422, "IMAGE_OCR_FAILED", "图片文字识别失败"),
        (
            ImageTextEmptyError,
            422,
            "IMAGE_TEXT_EMPTY",
            "当前图片未识别到足够的文字内容",
        ),
        (
            ImageTextTooLongError,
            413,
            "IMAGE_TEXT_TOO_LONG",
            "识别出的文字过长，当前版本暂不支持分析",
        ),
    ],
)
def test_image_errors_have_controlled_api_contract(
    error_type: type[ImageExtractionError],
    status_code: int,
    code: str,
    detail: str,
) -> None:
    app.dependency_overrides[get_image_analyze_service] = (
        lambda: FailedImageAnalyzeService(error_type())
    )

    response = client.post(
        "/analyze/image",
        files={"file": ("screen.png", b"content", "image/png")},
    )

    assert response.status_code == status_code
    assert response.json() == {"code": code, "detail": detail}


def test_image_endpoint_keeps_llm_failure_generic() -> None:
    app.dependency_overrides[get_image_analyze_service] = (
        lambda: FailedImageAnalyzeService(LlmServiceError("internal provider detail"))
    )

    response = client.post(
        "/analyze/image",
        files={"file": ("screen.png", b"content", "image/png")},
    )

    assert response.status_code == 503
    assert response.json() == {"detail": "LLM 服务暂不可用"}
