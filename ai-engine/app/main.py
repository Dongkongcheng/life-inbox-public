from typing import Annotated, Literal

from fastapi import Depends, FastAPI, File, Form, Request, UploadFile, status
from pydantic import BaseModel
from starlette.responses import JSONResponse

from app.config import LlmConfigurationError
from app.schemas.analyze import AnalyzeRequest, AnalyzeResult
from app.schemas.summary import SummaryRequest, SummaryResponse
from app.schemas.url_analyze import UrlAnalyzeRequest
from app.services.analyze_service import AnalyzeService
from app.services.document_text_extractor import (
    DocumentExtractionError,
    DocumentTextExtractor,
)
from app.services.file_analyze_service import FileAnalyzeService
from app.services.llm_client import (
    LlmClient,
    LlmInvalidResponseError,
    LlmServiceError,
    LlmTimeoutError,
)
from app.services.summary_service import SummaryService
from app.services.url_analyze_service import UrlAnalyzeService
from app.services.url_content_extractor import UrlContentError, UrlContentExtractor


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


@app.exception_handler(LlmConfigurationError)
def handle_llm_configuration_error(
    request: Request,
    exception: LlmConfigurationError,
) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        content={"detail": "LLM 配置不完整"},
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


@app.post("/analyze/url", response_model=AnalyzeResult)
def analyze_url(
    request: UrlAnalyzeRequest,
    service: UrlAnalyzeService = Depends(get_url_analyze_service),
) -> AnalyzeResult:
    """安全读取 URL 正文，再复用 TEXT 的统一 Analyze Pipeline。"""

    return service.analyze(request)


@app.post("/analyze/file", response_model=AnalyzeResult)
def analyze_file(
    file: Annotated[UploadFile, File()],
    title: Annotated[str | None, Form(max_length=255)] = None,
    service: FileAnalyzeService = Depends(get_file_analyze_service),
) -> AnalyzeResult:
    """接收 Java 管理的文件内容，提取文本后复用统一 Analyze Pipeline。"""

    return service.analyze(file, title)


@app.post("/summarize", response_model=SummaryResponse)
def summarize(
    request: SummaryRequest,
    service: SummaryService = Depends(get_summary_service),
) -> SummaryResponse:
    """兼容旧调用方；内部仍执行统一 Analyze，只投影 summary 字段。"""

    return service.summarize(request)
