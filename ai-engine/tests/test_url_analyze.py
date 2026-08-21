import httpx
import pytest
from fastapi.testclient import TestClient

from app.main import app, get_url_analyze_service
from app.schemas.analyze import MAX_ANALYZE_INPUT_CHARS, AnalyzeResult, PreparedContent
from app.schemas.url_analyze import MAX_URL_CHARS, UrlAnalyzeRequest
from app.services.url_analyze_service import UrlAnalyzeService
from app.services.url_content_extractor import (
    MAX_URL_REDIRECTS,
    MAX_URL_RESPONSE_BYTES,
    ExtractedUrlContent,
    UrlBlockedError,
    UrlContentEmptyError,
    UrlContentError,
    UrlContentExtractor,
    UrlContentTypeUnsupportedError,
    UrlFetchFailedError,
    UrlFetchTimeoutError,
    UrlInvalidError,
    UrlResponseTooLargeError,
)


client = TestClient(app)
PUBLIC_IP = "93.184.216.34"


def public_resolver(hostname: str, port: int) -> list[str]:
    return [PUBLIC_IP]


def successful_result() -> AnalyzeResult:
    return AnalyzeResult(
        summary="网页内容摘要",
        category="资讯",
        tags=["网页文章"],
        keywords=["正文提取"],
        entities=[],
    )


class RecordingAnalyzeService:
    def __init__(self) -> None:
        self.requests = []

    def analyze(self, request) -> AnalyzeResult:
        self.requests.append(request)
        return successful_result()


class StaticUrlAnalyzeService:
    def analyze(self, request) -> AnalyzeResult:
        return successful_result()

    def prepare(self, request) -> PreparedContent:
        return PreparedContent(title=request.title or "网页标题", text="网页提取正文")


class FailedUrlAnalyzeService:
    def __init__(self, error: UrlContentError) -> None:
        self._error = error

    def analyze(self, request) -> AnalyzeResult:
        raise self._error

    def prepare(self, request) -> PreparedContent:
        raise self._error


class StaticContentExtractor:
    def __init__(self, content: ExtractedUrlContent) -> None:
        self._content = content

    def extract(self, url: str) -> ExtractedUrlContent:
        return self._content


class ChunkedBody(httpx.SyncByteStream):
    def __init__(self, *chunks: bytes) -> None:
        self._chunks = chunks

    def __iter__(self):
        yield from self._chunks


@pytest.fixture(autouse=True)
def clear_dependency_overrides():
    yield
    app.dependency_overrides.clear()


def test_url_request_uses_java_compatible_length_limit() -> None:
    UrlAnalyzeRequest(url="https://example.com/" + "x" * (MAX_URL_CHARS - 20))

    with pytest.raises(ValueError):
        UrlAnalyzeRequest(url="https://example.com/" + "x" * MAX_URL_CHARS)


def test_extracts_article_and_removes_obvious_page_noise() -> None:
    captured_request: httpx.Request | None = None
    resolver_calls: list[tuple[str, int]] = []

    def resolver(hostname: str, port: int) -> list[str]:
        resolver_calls.append((hostname, port))
        return [PUBLIC_IP]

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal captured_request
        captured_request = request
        return httpx.Response(
            200,
            headers={"Content-Type": "text/html; charset=utf-8"},
            content="""
                <html>
                  <head>
                    <title>测试网页标题</title>
                    <style>.hidden { display: none; }</style>
                  </head>
                  <body>
                    <header>站点页眉</header>
                    <nav>导航菜单</nav>
                    <div id="cookie-consent">Cookie 提示</div>
                    <div class="promo-banner">广告横幅</div>
                    <script>危险脚本内容</script>
                    <article>
                      <h1>正文标题</h1>
                      <p>这是第一段正文。</p>
                      <p>这是第二段正文。</p>
                    </article>
                    <main>不应覆盖 article 的备用正文</main>
                    <footer>站点页脚</footer>
                  </body>
                </html>
            """.encode(),
        )

    extractor = UrlContentExtractor(
        transport=httpx.MockTransport(handler),
        resolver=resolver,
    )

    extracted = extractor.extract("https://example.com/articles/1#section")

    assert extracted.title == "测试网页标题"
    assert extracted.text == "正文标题 这是第一段正文。 这是第二段正文。"
    assert "导航菜单" not in extracted.text
    assert "危险脚本内容" not in extracted.text
    assert "站点页眉" not in extracted.text
    assert "Cookie 提示" not in extracted.text
    assert "广告横幅" not in extracted.text
    assert "备用正文" not in extracted.text
    assert resolver_calls == [("example.com", 443)]
    assert captured_request is not None
    assert str(captured_request.url) == f"https://{PUBLIC_IP}/articles/1"
    assert captured_request.headers["Host"] == "example.com"
    assert captured_request.extensions["sni_hostname"] == "example.com"
    assert captured_request.extensions["timeout"] == {
        "connect": 3.0,
        "read": 8.0,
        "write": 3.0,
        "pool": 3.0,
    }


@pytest.mark.parametrize(
    ("html", "expected_text"),
    [
        ("<main><p>Main 正文</p></main>", "Main 正文"),
        ("<body><p>Body 正文</p></body>", "Body 正文"),
    ],
    ids=["main", "body"],
)
def test_content_container_falls_back_from_main_to_body(
    html: str,
    expected_text: str,
) -> None:
    extractor = UrlContentExtractor(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                headers={"Content-Type": "text/html"},
                content=html.encode(),
            )
        ),
        resolver=public_resolver,
    )

    extracted = extractor.extract("http://example.com/page")

    assert extracted.text == expected_text


@pytest.mark.parametrize(
    "url",
    [
        "file:///etc/passwd",
        "ftp://example.com/file",
        "data:text/html,hello",
        "javascript:alert(1)",
        "https://user:password@example.com/article",
        "not-a-url",
    ],
)
def test_rejects_invalid_or_unsupported_urls(url: str) -> None:
    extractor = UrlContentExtractor(resolver=public_resolver)

    with pytest.raises(UrlInvalidError):
        extractor.extract(url)


@pytest.mark.parametrize(
    "url",
    [
        "http://localhost/article",
        "http://127.0.0.1/article",
        "http://10.0.0.1/article",
        "http://192.168.1.1/article",
        "http://169.254.169.254/latest/meta-data",
        "http://224.0.0.1/article",
        "http://[::1]/article",
        "http://[fc00::1]/article",
        "http://[fe80::1]/article",
        "http://[ff02::1]/article",
        "http://[fec0::1]/article",
        "http://[::ffff:127.0.0.1]/article",
    ],
)
def test_rejects_local_private_and_link_local_addresses(url: str) -> None:
    extractor = UrlContentExtractor(resolver=public_resolver)

    with pytest.raises(UrlBlockedError):
        extractor.extract(url)


def test_rejects_hostname_if_any_dns_address_is_not_global() -> None:
    extractor = UrlContentExtractor(
        resolver=lambda hostname, port: [PUBLIC_IP, "10.0.0.8"],
    )

    with pytest.raises(UrlBlockedError):
        extractor.extract("https://mixed.example/article")


def test_redirect_revalidates_dns_and_blocks_private_destination() -> None:
    requests: list[httpx.Request] = []
    resolver_calls: list[str] = []

    def resolver(hostname: str, port: int) -> list[str]:
        resolver_calls.append(hostname)
        return [PUBLIC_IP] if hostname == "public.example" else ["10.0.0.9"]

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            302,
            headers={"Location": "http://private.example/internal"},
        )

    extractor = UrlContentExtractor(
        transport=httpx.MockTransport(handler),
        resolver=resolver,
    )

    with pytest.raises(UrlBlockedError):
        extractor.extract("https://public.example/start")

    assert resolver_calls == ["public.example", "private.example"]
    assert len(requests) == 1
    assert requests[0].url.host == PUBLIC_IP


def test_public_redirect_re_resolves_and_re_pins_host_and_sni() -> None:
    requests: list[httpx.Request] = []
    address_by_host = {
        "first.example": "93.184.216.34",
        "second.example": "142.250.72.14",
    }

    def resolver(hostname: str, port: int) -> list[str]:
        return [address_by_host[hostname]]

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if len(requests) == 1:
            return httpx.Response(
                302,
                headers={"Location": "https://second.example/final"},
            )
        return httpx.Response(
            200,
            headers={"Content-Type": "text/html"},
            content=b"<article>redirected body</article>",
        )

    extractor = UrlContentExtractor(
        transport=httpx.MockTransport(handler),
        resolver=resolver,
    )

    extracted = extractor.extract("https://first.example/start")

    assert extracted.text == "redirected body"
    assert [request.url.host for request in requests] == [
        address_by_host["first.example"],
        address_by_host["second.example"],
    ]
    assert [request.headers["Host"] for request in requests] == [
        "first.example",
        "second.example",
    ]
    assert [request.extensions["sni_hostname"] for request in requests] == [
        "first.example",
        "second.example",
    ]


def test_rejects_redirect_chain_beyond_limit() -> None:
    request_count = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal request_count
        request_count += 1
        return httpx.Response(302, headers={"Location": f"/hop-{request_count}"})

    extractor = UrlContentExtractor(
        transport=httpx.MockTransport(handler),
        resolver=public_resolver,
    )

    with pytest.raises(UrlFetchFailedError):
        extractor.extract("http://example.com/start")

    assert request_count == MAX_URL_REDIRECTS + 1


def test_maps_http_timeout_to_url_timeout_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("mock timeout", request=request)

    extractor = UrlContentExtractor(
        transport=httpx.MockTransport(handler),
        resolver=public_resolver,
    )

    with pytest.raises(UrlFetchTimeoutError):
        extractor.extract("https://example.com/slow")


@pytest.mark.parametrize("content_type", ["application/pdf", "image/png", ""])
def test_rejects_non_html_content_type(content_type: str) -> None:
    extractor = UrlContentExtractor(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                headers={"Content-Type": content_type},
                content=b"binary",
            )
        ),
        resolver=public_resolver,
    )

    with pytest.raises(UrlContentTypeUnsupportedError):
        extractor.extract("https://example.com/file")


def test_rejects_declared_response_larger_than_limit() -> None:
    extractor = UrlContentExtractor(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                headers={
                    "Content-Type": "text/html",
                    "Content-Length": str(MAX_URL_RESPONSE_BYTES + 1),
                },
                content=b"",
            )
        ),
        resolver=public_resolver,
    )

    with pytest.raises(UrlResponseTooLargeError):
        extractor.extract("https://example.com/large")


def test_rejects_streamed_response_larger_than_limit() -> None:
    oversized_stream = ChunkedBody(
        b"<article>",
        b"x" * MAX_URL_RESPONSE_BYTES,
        b"</article>",
    )
    extractor = UrlContentExtractor(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                headers={"Content-Type": "text/html"},
                stream=oversized_stream,
            )
        ),
        resolver=public_resolver,
    )

    with pytest.raises(UrlResponseTooLargeError):
        extractor.extract("https://example.com/chunked-large")


def test_empty_content_does_not_call_analyze_service() -> None:
    extractor = UrlContentExtractor(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                headers={"Content-Type": "text/html"},
                content=(
                    b"<html><head><title>only title</title></head>"
                    b"<nav>only navigation</nav><script>only script</script></html>"
                ),
            )
        ),
        resolver=public_resolver,
    )
    analyze_service = RecordingAnalyzeService()
    service = UrlAnalyzeService(extractor, analyze_service)

    with pytest.raises(UrlContentEmptyError):
        service.analyze(UrlAnalyzeRequest(url="https://example.com/empty"))

    assert analyze_service.requests == []


def test_extracted_text_is_limited_before_analyze() -> None:
    extractor = UrlContentExtractor(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                headers={"Content-Type": "text/html"},
                content=("<article>" + "x" * 20_100 + "</article>").encode(),
            )
        ),
        resolver=public_resolver,
    )

    extracted = extractor.extract("https://example.com/long-article")

    assert len(extracted.text) == MAX_ANALYZE_INPUT_CHARS


def test_url_analyze_service_reuses_single_analyze_pipeline() -> None:
    extractor = StaticContentExtractor(
        ExtractedUrlContent(title="网页标题", text="网页提取后的正文")
    )
    analyze_service = RecordingAnalyzeService()
    service = UrlAnalyzeService(extractor, analyze_service)

    result = service.analyze(
        UrlAnalyzeRequest(
            url="https://example.com/article",
            title="InboxItem 标题",
        )
    )

    assert result == successful_result()
    assert len(analyze_service.requests) == 1
    assert analyze_service.requests[0].title == "InboxItem 标题"
    assert analyze_service.requests[0].text == "网页提取后的正文"


def test_url_prepare_returns_extracted_content_without_calling_llm() -> None:
    extractor = StaticContentExtractor(
        ExtractedUrlContent(title="网页标题", text="网页提取后的正文")
    )
    analyze_service = RecordingAnalyzeService()
    service = UrlAnalyzeService(extractor, analyze_service)

    prepared = service.prepare(
        UrlAnalyzeRequest(url="https://example.com/article", title=None)
    )

    assert prepared == PreparedContent(title="网页标题", text="网页提取后的正文")
    assert analyze_service.requests == []


def test_url_analyze_endpoint_returns_existing_analyze_result() -> None:
    app.dependency_overrides[get_url_analyze_service] = lambda: StaticUrlAnalyzeService()

    response = client.post(
        "/analyze/url",
        json={"url": "https://example.com/article", "title": "测试"},
    )

    assert response.status_code == 200
    assert response.json() == {
        "summary": "网页内容摘要",
        "category": "资讯",
        "tags": ["网页文章"],
        "keywords": ["正文提取"],
        "entities": [],
    }


def test_url_prepare_endpoint_returns_plain_content() -> None:
    app.dependency_overrides[get_url_analyze_service] = lambda: StaticUrlAnalyzeService()

    response = client.post(
        "/prepare/url",
        json={"url": "https://example.com/article", "title": "用户标题"},
    )

    assert response.status_code == 200
    assert response.json() == {"title": "用户标题", "text": "网页提取正文"}


@pytest.mark.parametrize(
    ("error_type", "status_code", "code", "detail"),
    [
        (UrlInvalidError, 400, "URL_INVALID", "URL 无效，仅支持 http/https 网页地址"),
        (UrlBlockedError, 403, "URL_BLOCKED", "URL 被安全策略阻止"),
        (UrlFetchTimeoutError, 408, "URL_FETCH_TIMEOUT", "网页请求超时"),
        (UrlFetchFailedError, 424, "URL_FETCH_FAILED", "网页访问失败"),
        (
            UrlContentTypeUnsupportedError,
            415,
            "URL_CONTENT_TYPE_UNSUPPORTED",
            "网页内容类型不受支持",
        ),
        (
            UrlResponseTooLargeError,
            413,
            "URL_RESPONSE_TOO_LARGE",
            "网页响应超过大小限制",
        ),
        (UrlContentEmptyError, 422, "URL_CONTENT_EMPTY", "无法从网页提取有效正文"),
    ],
)
def test_url_extraction_errors_have_controlled_api_contract(
    error_type,
    status_code: int,
    code: str,
    detail: str,
) -> None:
    app.dependency_overrides[get_url_analyze_service] = lambda: FailedUrlAnalyzeService(
        error_type()
    )

    response = client.post("/analyze/url", json={"url": "https://example.com"})

    assert response.status_code == status_code
    assert response.json() == {"code": code, "detail": detail}
