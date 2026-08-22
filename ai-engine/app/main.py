from typing import Annotated, Literal

from fastapi import Depends, FastAPI, File, Form, Path, Request, UploadFile, status
from pydantic import BaseModel
from starlette.responses import JSONResponse

from app.config import (
    EmbeddingConfigurationError,
    LlmConfigurationError,
    VectorStoreConfigurationError,
)
from app.schemas.analyze import AnalyzeRequest, AnalyzeResult, PreparedContent
from app.schemas.embedding import EmbeddingRequest, EmbeddingResult
from app.schemas.summary import SummaryRequest, SummaryResponse
from app.schemas.url_analyze import UrlAnalyzeRequest
from app.schemas.vector_index import (
    VectorDeleteResult,
    VectorIndexRequest,
    VectorIndexResult,
)
from app.services.analyze_service import AnalyzeService
from app.services.document_text_extractor import (
    DocumentExtractionError,
    DocumentTextExtractor,
)
from app.services.embedding_client import (
    EmbeddingClient,
    EmbeddingInvalidResponseError,
    EmbeddingServiceError,
    EmbeddingTimeoutError,
)
from app.services.embedding_service import EmbeddingService
from app.services.file_analyze_service import FileAnalyzeService
from app.services.image_analyze_service import ImageAnalyzeService
from app.services.image_text_extractor import ImageExtractionError, ImageTextExtractor
from app.services.llm_client import (
    LlmClient,
    LlmInvalidResponseError,
    LlmServiceError,
    LlmTimeoutError,
)
from app.services.summary_service import SummaryService
from app.services.url_analyze_service import UrlAnalyzeService
from app.services.url_content_extractor import UrlContentError, UrlContentExtractor
from app.services.vector_index_service import VectorIndexService
from app.services.vector_store_service import (
    VectorStoreCompatibilityError,
    VectorStoreError,
    VectorStoreInvalidResponseError,
    VectorStoreService,
    VectorStoreTimeoutError,
)


class HealthResponse(BaseModel):
    """Java 用这个固定结构确认 AI 服务已启动且协议可解析。"""

    status: Literal["ok"]
    service: Literal["life-inbox-ai"]


# 当前入口只负责启动独立 AI 服务；Python 不连接 MySQL，也不拥有 InboxItem 数据。
app = FastAPI(title="LifeInbox AI Engine")
llm_client = LlmClient()
analyze_service = AnalyzeService(llm_client)
summary_service = SummaryService(analyze_service)
url_content_extractor = UrlContentExtractor()
url_analyze_service = UrlAnalyzeService(url_content_extractor, analyze_service)
document_text_extractor = DocumentTextExtractor()
file_analyze_service = FileAnalyzeService(document_text_extractor, analyze_service)
image_text_extractor = ImageTextExtractor()
image_analyze_service = ImageAnalyzeService(image_text_extractor, analyze_service)
embedding_client = EmbeddingClient()
embedding_service = EmbeddingService(embedding_client)
vector_store_service = VectorStoreService()
vector_index_service = VectorIndexService(embedding_service, vector_store_service)


def get_analyze_service() -> AnalyzeService:
    """提供统一 Analyze Service，测试可以替换 LLM 而不访问真实供应商。"""

    return analyze_service


def get_summary_service() -> SummaryService:
    """旧 Summary 入口保留独立依赖点，但底层仍复用 Analyze Service。"""

    return summary_service


def get_url_analyze_service() -> UrlAnalyzeService:
    """提供 URL Analyze 编排服务，测试可以同时替换网页抓取和 LLM。"""

    return url_analyze_service


def get_file_analyze_service() -> FileAnalyzeService:
    """提供 FILE Analyze 编排服务，测试可以替换文档解析和 LLM。"""

    return file_analyze_service


def get_image_analyze_service() -> ImageAnalyzeService:
    """提供 IMAGE OCR Analyze 编排服务，测试可替换 OCR 与 LLM。"""

    return image_analyze_service


def get_embedding_service() -> EmbeddingService:
    """Embedding 配置按请求读取，未配置时不阻止 FastAPI 与既有能力启动。"""

    return embedding_service


def get_vector_index_service() -> VectorIndexService:
    """Qdrant Client 与配置都在真实索引时惰性初始化，健康检查不依赖 Vector Store。"""

    return vector_index_service


@app.exception_handler(LlmConfigurationError)
def handle_llm_configuration_error(
    request: Request,
    exception: LlmConfigurationError,
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        content={"detail": "LLM 配置不完整"},
    )


@app.exception_handler(EmbeddingConfigurationError)
def handle_embedding_configuration_error(
    request: Request,
    exception: EmbeddingConfigurationError,
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        content={"detail": "Embedding 配置不完整"},
    )


@app.exception_handler(EmbeddingTimeoutError)
def handle_embedding_timeout(
    request: Request,
    exception: EmbeddingTimeoutError,
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_504_GATEWAY_TIMEOUT,
        content={"detail": "Embedding 请求超时"},
    )


@app.exception_handler(EmbeddingInvalidResponseError)
def handle_invalid_embedding_response(
    request: Request,
    exception: EmbeddingInvalidResponseError,
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_502_BAD_GATEWAY,
        content={"detail": "Embedding 返回的向量无效"},
    )


@app.exception_handler(EmbeddingServiceError)
def handle_embedding_service_error(
    request: Request,
    exception: EmbeddingServiceError,
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        content={"detail": "Embedding 服务暂不可用"},
    )


@app.exception_handler(VectorStoreConfigurationError)
def handle_vector_store_configuration_error(
    request: Request,
    exception: VectorStoreConfigurationError,
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        content={"detail": "Vector Store 配置不完整"},
    )


@app.exception_handler(VectorStoreTimeoutError)
def handle_vector_store_timeout(
    request: Request,
    exception: VectorStoreTimeoutError,
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_504_GATEWAY_TIMEOUT,
        content={"detail": "Vector Store 请求超时"},
    )


@app.exception_handler(VectorStoreCompatibilityError)
def handle_vector_store_compatibility_error(
    request: Request,
    exception: VectorStoreCompatibilityError,
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_409_CONFLICT,
        content={"detail": "Vector Collection 与当前 Embedding 不兼容"},
    )


@app.exception_handler(VectorStoreInvalidResponseError)
def handle_vector_store_invalid_response(
    request: Request,
    exception: VectorStoreInvalidResponseError,
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_502_BAD_GATEWAY,
        content={"detail": "Vector Store 返回无效响应"},
    )


@app.exception_handler(VectorStoreError)
def handle_vector_store_error(
    request: Request,
    exception: VectorStoreError,
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        content={"detail": "Vector Store 暂不可用"},
    )


@app.exception_handler(LlmTimeoutError)
def handle_llm_timeout(
    request: Request,
    exception: LlmTimeoutError,
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_504_GATEWAY_TIMEOUT,
        content={"detail": "LLM 请求超时"},
    )


@app.exception_handler(LlmInvalidResponseError)
def handle_invalid_llm_response(
    request: Request,
    exception: LlmInvalidResponseError,
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_502_BAD_GATEWAY,
        content={"detail": "LLM 返回的分析结果无效"},
    )


@app.exception_handler(LlmServiceError)
def handle_llm_service_error(
    request: Request,
    exception: LlmServiceError,
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        content={"detail": "LLM 服务暂不可用"},
    )


@app.exception_handler(UrlContentError)
def handle_url_content_error(
    request: Request,
    exception: UrlContentError,
) -> JSONResponse:
    """只返回固定错误码和公开描述，不暴露目标地址、IP 或上游响应。"""

    return JSONResponse(
        status_code=exception.status_code,
        content={"code": exception.code, "detail": exception.detail},
    )


@app.exception_handler(DocumentExtractionError)
def handle_document_extraction_error(
    request: Request,
    exception: DocumentExtractionError,
) -> JSONResponse:
    """只返回固定文档错误码，不暴露文件内容或 PDF 解析器内部异常。"""

    return JSONResponse(
        status_code=exception.status_code,
        content={"code": exception.code, "detail": exception.detail},
    )


@app.exception_handler(ImageExtractionError)
def handle_image_extraction_error(
    request: Request,
    exception: ImageExtractionError,
) -> JSONResponse:
    """只返回固定 OCR 错误码，不暴露图片内容、模型路径或内部异常。"""

    return JSONResponse(
        status_code=exception.status_code,
        content={"code": exception.code, "detail": exception.detail},
    )


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    """提供轻量健康检查，为 Java 与 Python 的第一条通信链路服务。"""

    return HealthResponse(status="ok", service="life-inbox-ai")


@app.post("/analyze", response_model=AnalyzeResult)
def analyze(
    request: AnalyzeRequest,
    service: AnalyzeService = Depends(get_analyze_service),
) -> AnalyzeResult:
    """一次分析 TEXT 并返回 Summary、Category、Tags、Keywords 和 Entities。"""

    return service.analyze(request)


@app.post("/embedding", response_model=EmbeddingResult)
def embedding(
    request: EmbeddingRequest,
    service: EmbeddingService = Depends(get_embedding_service),
) -> EmbeddingResult:
    """内部能力仅执行 Text → Vector；不保存向量，也不触发索引或搜索。"""

    return service.embed(request)


@app.post("/vector/index", response_model=VectorIndexResult)
def index_vector(
    request: VectorIndexRequest,
    service: VectorIndexService = Depends(get_vector_index_service),
) -> VectorIndexResult:
    """内部按条目生成并 Upsert 最新向量；不提供产品搜索或向量读取能力。"""

    return service.index(request)


@app.delete("/vector/index/{inbox_item_id}", response_model=VectorDeleteResult)
def delete_vector(
    inbox_item_id: Annotated[int, Path(gt=0)],
    service: VectorIndexService = Depends(get_vector_index_service),
) -> VectorDeleteResult:
    """内部幂等删除稳定 Point ID；Qdrant 故障由 Java 业务层按增强能力降级。"""

    return service.delete(inbox_item_id)


@app.post("/analyze/url", response_model=AnalyzeResult)
def analyze_url(
    request: UrlAnalyzeRequest,
    service: UrlAnalyzeService = Depends(get_url_analyze_service),
) -> AnalyzeResult:
    """安全读取 URL 正文，再复用 TEXT 的统一 Analyze Pipeline。"""

    return service.analyze(request)


@app.post("/prepare/url", response_model=PreparedContent)
def prepare_url(
    request: UrlAnalyzeRequest,
    service: UrlAnalyzeService = Depends(get_url_analyze_service),
) -> PreparedContent:
    """仅安全提取 URL 正文，供 Java 在 LLM 调用前持久化派生检索内容。"""

    return service.prepare(request)


@app.post("/analyze/file", response_model=AnalyzeResult)
def analyze_file(
    file: Annotated[UploadFile, File()],
    title: Annotated[str | None, Form(max_length=255)] = None,
    service: FileAnalyzeService = Depends(get_file_analyze_service),
) -> AnalyzeResult:
    """接收 Java 管理的文件内容，提取文本后复用统一 Analyze Pipeline。"""

    return service.analyze(file, title)


@app.post("/prepare/file", response_model=PreparedContent)
def prepare_file(
    file: Annotated[UploadFile, File()],
    title: Annotated[str | None, Form(max_length=255)] = None,
    service: FileAnalyzeService = Depends(get_file_analyze_service),
) -> PreparedContent:
    """仅复用现有 TXT/Markdown/PDF 提取器，不调用 LLM。"""

    return service.prepare(file, title)


@app.post("/analyze/image", response_model=AnalyzeResult)
def analyze_image(
    file: Annotated[UploadFile, File()],
    title: Annotated[str | None, Form(max_length=255)] = None,
    service: ImageAnalyzeService = Depends(get_image_analyze_service),
) -> AnalyzeResult:
    """接收 Java 管理的图片内容，本地 OCR 后复用统一 Analyze Pipeline。"""

    return service.analyze(file, title)


@app.post("/prepare/image", response_model=PreparedContent)
def prepare_image(
    file: Annotated[UploadFile, File()],
    title: Annotated[str | None, Form(max_length=255)] = None,
    service: ImageAnalyzeService = Depends(get_image_analyze_service),
) -> PreparedContent:
    """仅复用现有 OCR 生成纯文本，不调用 LLM 或 Vision 模型。"""

    return service.prepare(file, title)


@app.post("/summarize", response_model=SummaryResponse)
def summarize(
    request: SummaryRequest,
    service: SummaryService = Depends(get_summary_service),
) -> SummaryResponse:
    """兼容旧调用方；内部仍执行统一 Analyze，只投影 summary 字段。"""

    return service.summarize(request)
