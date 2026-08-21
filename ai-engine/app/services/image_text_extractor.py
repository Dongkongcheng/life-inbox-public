from __future__ import annotations

import threading
import unicodedata
import warnings
from collections.abc import Callable, Sequence
from io import BytesIO
from pathlib import Path
from typing import BinaryIO, Protocol

from PIL import Image, ImageOps, UnidentifiedImageError

from app.schemas.analyze import MAX_ANALYZE_INPUT_CHARS


MAX_IMAGE_ANALYZE_BYTES = 10 * 1024 * 1024
MAX_IMAGE_SIDE = 10_000
MAX_IMAGE_PIXELS = 20_000_000
MIN_MEANINGFUL_CHARS = 4

_SUPPORTED_EXTENSIONS = {"jpg", "jpeg", "png", "webp"}
_ALLOWED_CONTENT_TYPES = {
    "jpg": {"image/jpeg"},
    "jpeg": {"image/jpeg"},
    "png": {"image/png"},
    "webp": {"image/webp"},
}
_EXPECTED_IMAGE_FORMAT = {
    "jpg": "JPEG",
    "jpeg": "JPEG",
    "png": "PNG",
    "webp": "WEBP",
}


class ImageExtractionError(Exception):
    """可以安全映射到 Java 产品层的图片 OCR 业务错误。"""

    status_code = 422
    code = "IMAGE_OCR_FAILED"
    detail = "图片文字识别失败"


class ImageTypeUnsupportedError(ImageExtractionError):
    status_code = 415
    code = "IMAGE_TYPE_UNSUPPORTED"
    detail = "当前只支持 JPG、PNG 和 WEBP 图片分析"


class ImageTooLargeError(ImageExtractionError):
    status_code = 413
    code = "IMAGE_TOO_LARGE"
    detail = "用于 AI 分析的图片不能超过 10MB"


class ImageDimensionsTooLargeError(ImageExtractionError):
    status_code = 413
    code = "IMAGE_DIMENSIONS_TOO_LARGE"
    detail = "图片分辨率过大，当前版本暂不支持分析"


class ImageInvalidError(ImageExtractionError):
    code = "IMAGE_INVALID"
    detail = "图片文件损坏或内容无效"


class ImageOcrFailedError(ImageExtractionError):
    code = "IMAGE_OCR_FAILED"
    detail = "图片文字识别失败"


class ImageTextEmptyError(ImageExtractionError):
    code = "IMAGE_TEXT_EMPTY"
    detail = "当前图片未识别到足够的文字内容"


class ImageTextTooLongError(ImageExtractionError):
    status_code = 413
    code = "IMAGE_TEXT_TOO_LONG"
    detail = "识别出的文字过长，当前版本暂不支持分析"


class OcrOutput(Protocol):
    txts: Sequence[str] | None


class OcrEngine(Protocol):
    def __call__(self, image: Image.Image) -> OcrOutput: ...


def _create_rapid_ocr_engine() -> OcrEngine:
    # 延迟导入和初始化，使健康检查不必等待本地 OCR 模型加载。
    from rapidocr import RapidOCR

    return RapidOCR()


class ImageTextExtractor:
    """校验图片并使用本地 OCR 提取文字，不负责 LLM 或业务数据。"""

    def __init__(
        self,
        engine_factory: Callable[[], OcrEngine] | None = None,
    ) -> None:
        self._engine_factory = engine_factory or _create_rapid_ocr_engine
        self._engine: OcrEngine | None = None
        # RapidOCR 模型只初始化一次；串行调用也避免共享推理 Session 的并发状态问题。
        self._engine_lock = threading.Lock()

    def extract(
        self,
        filename: str | None,
        content_type: str | None,
        stream: BinaryIO,
    ) -> str:
        extension = self._validate_type(filename, content_type)
        content = stream.read(MAX_IMAGE_ANALYZE_BYTES + 1)
        if len(content) > MAX_IMAGE_ANALYZE_BYTES:
            raise ImageTooLargeError()
        if not content:
            raise ImageInvalidError()

        image = self._load_image(content, extension)
        try:
            with self._engine_lock:
                if self._engine is None:
                    self._engine = self._engine_factory()
                result = self._engine(image)
        except ImageExtractionError:
            raise
        except Exception as exception:
            raise ImageOcrFailedError() from exception
        finally:
            image.close()

        text = self._normalize_ocr_text(getattr(result, "txts", None))
        if self._meaningful_character_count(text) < MIN_MEANINGFUL_CHARS:
            # 无有效文字时直接停止，避免把标点或单个数字发送给 LLM 消耗 Token。
            raise ImageTextEmptyError()
        if len(text) > MAX_ANALYZE_INPUT_CHARS:
            raise ImageTextTooLongError()
        return text

    def _validate_type(self, filename: str | None, content_type: str | None) -> str:
        suffix = Path(filename or "").suffix.lower()
        extension = suffix.removeprefix(".")
        if extension not in _SUPPORTED_EXTENSIONS:
            raise ImageTypeUnsupportedError()

        normalized_content_type = (content_type or "").split(";", 1)[0].strip().lower()
        if normalized_content_type not in _ALLOWED_CONTENT_TYPES[extension]:
            raise ImageTypeUnsupportedError()
        return extension

    def _load_image(self, content: bytes, extension: str) -> Image.Image:
        try:
            with warnings.catch_warnings():
                # 不关闭 Pillow 的解压炸弹保护；把警告升级为受控业务失败。
                warnings.simplefilter("error", Image.DecompressionBombWarning)
                with Image.open(BytesIO(content)) as opened_image:
                    if opened_image.format != _EXPECTED_IMAGE_FORMAT[extension]:
                        raise ImageInvalidError()
                    width, height = opened_image.size
                    if (
                        width <= 0
                        or height <= 0
                        or width > MAX_IMAGE_SIDE
                        or height > MAX_IMAGE_SIDE
                        or width * height > MAX_IMAGE_PIXELS
                    ):
                        raise ImageDimensionsTooLargeError()

                    opened_image.load()
                    # 手机照片可能依赖 EXIF Orientation；OCR 前做一次简单方向归一化。
                    return ImageOps.exif_transpose(opened_image).convert("RGB").copy()
        except (ImageDimensionsTooLargeError, ImageInvalidError):
            raise
        except (Image.DecompressionBombError, Image.DecompressionBombWarning) as exception:
            raise ImageDimensionsTooLargeError() from exception
        except (UnidentifiedImageError, OSError, SyntaxError, ValueError) as exception:
            raise ImageInvalidError() from exception

    def _normalize_ocr_text(self, texts: Sequence[str] | None) -> str:
        if not texts:
            return ""

        normalized_lines: list[str] = []
        for value in texts:
            if not isinstance(value, str):
                raise ImageOcrFailedError()
            normalized = " ".join(unicodedata.normalize("NFKC", value).split())
            if normalized:
                normalized_lines.append(normalized)
        return "\n".join(normalized_lines).strip()

    def _meaningful_character_count(self, text: str) -> int:
        return sum(character.isalnum() for character in text)
