import ipaddress
import re
import socket
from collections.abc import Callable, Sequence
from dataclasses import dataclass

import httpx
from bs4 import BeautifulSoup, Tag

from app.schemas.analyze import MAX_ANALYZE_INPUT_CHARS


URL_CONNECT_TIMEOUT_SECONDS = 3.0
URL_READ_TIMEOUT_SECONDS = 8.0
MAX_URL_REDIRECTS = 5
MAX_URL_RESPONSE_BYTES = 1_048_576
ALLOWED_HTML_CONTENT_TYPES = frozenset({"text/html", "application/xhtml+xml"})

_REDIRECT_STATUS_CODES = frozenset({301, 302, 303, 307, 308})
_REMOVED_TAG_NAMES = (
    "script",
    "style",
    "noscript",
    "template",
    "nav",
    "header",
    "footer",
    "aside",
    "form",
    "iframe",
    "svg",
    "canvas",
)
_REMOVED_ROLES = frozenset({"navigation", "banner", "contentinfo", "complementary"})
_NOISE_NAME_PATTERN = re.compile(
    r"(?:^|[-_\s])(cookie|consent|sidebar|advert|advertisement|menu|banner)(?:$|[-_\s])",
    re.IGNORECASE,
)

AddressResolver = Callable[[str, int], Sequence[str]]


class UrlContentError(RuntimeError):
    """网页读取的公开错误；固定 code/detail 避免泄露内部网络信息。"""

    code = "URL_FETCH_FAILED"
    detail = "网页访问失败"
    status_code = 424

    def __init__(self) -> None:
        super().__init__(self.detail)


class UrlInvalidError(UrlContentError):
    code = "URL_INVALID"
    detail = "URL 无效，仅支持 http/https 网页地址"
    status_code = 400


class UrlBlockedError(UrlContentError):
    code = "URL_BLOCKED"
    detail = "URL 被安全策略阻止"
    status_code = 403


class UrlFetchTimeoutError(UrlContentError):
    code = "URL_FETCH_TIMEOUT"
    detail = "网页请求超时"
    status_code = 408


class UrlFetchFailedError(UrlContentError):
    code = "URL_FETCH_FAILED"
    detail = "网页访问失败"
    status_code = 424


class UrlContentTypeUnsupportedError(UrlContentError):
    code = "URL_CONTENT_TYPE_UNSUPPORTED"
    detail = "网页内容类型不受支持"
    status_code = 415


class UrlResponseTooLargeError(UrlContentError):
    code = "URL_RESPONSE_TOO_LARGE"
    detail = "网页响应超过大小限制"
    status_code = 413


class UrlContentEmptyError(UrlContentError):
    code = "URL_CONTENT_EMPTY"
    detail = "无法从网页提取有效正文"
    status_code = 422


@dataclass(frozen=True)
class ExtractedUrlContent:
    """网页抓取后的内部结果，不扩展对 Java 暴露的 AnalyzeResult。"""

    title: str | None
    text: str


@dataclass(frozen=True)
class _SafeRequestTarget:
    logical_url: httpx.URL
    pinned_url: httpx.URL
    host_header: str
    sni_hostname: str | None


class UrlContentExtractor:
    """安全抓取普通 HTML，并提取可交给统一 Analyze 的纯文本。"""

    def __init__(
        self,
        transport: httpx.BaseTransport | None = None,
        resolver: AddressResolver | None = None,
    ) -> None:
        self._transport = transport
        self._resolver = resolver or _resolve_host_addresses

    def extract(self, url: str) -> ExtractedUrlContent:
        current_url = url
        timeout = httpx.Timeout(
            connect=URL_CONNECT_TIMEOUT_SECONDS,
            read=URL_READ_TIMEOUT_SECONDS,
            write=URL_CONNECT_TIMEOUT_SECONDS,
            pool=URL_CONNECT_TIMEOUT_SECONDS,
        )

        # 禁用环境代理和自动跳转，确保每一跳都经过相同的 SSRF 校验。
        with httpx.Client(
            timeout=timeout,
            follow_redirects=False,
            trust_env=False,
            transport=self._transport,
        ) as client:
            for redirect_count in range(MAX_URL_REDIRECTS + 1):
                target = self._prepare_safe_target(current_url)
                request = client.build_request(
                    "GET",
                    target.pinned_url,
                    headers={
                        "Host": target.host_header,
                        "User-Agent": "LifeInbox-AI/0.2 URLContentExtractor",
                        "Accept": "text/html, application/xhtml+xml",
                        "Accept-Encoding": "identity",
                        # 每跳使用独立连接，避免不同域名共享同一 IP 时复用错误的 TLS 会话。
                        "Connection": "close",
                    },
                    extensions=(
                        {"sni_hostname": target.sni_hostname}
                        if target.sni_hostname is not None
                        else None
                    ),
                )

                try:
                    response = client.send(request, stream=True)
                    try:
                        if response.status_code in _REDIRECT_STATUS_CODES:
                            if redirect_count >= MAX_URL_REDIRECTS:
                                raise UrlFetchFailedError()
                            location = response.headers.get("location")
                            if not location:
                                raise UrlFetchFailedError()
                            try:
                                current_url = str(target.logical_url.join(location))
                            except (TypeError, ValueError, httpx.InvalidURL) as exception:
                                raise UrlFetchFailedError() from exception
                            continue

                        try:
                            response.raise_for_status()
                        except httpx.HTTPStatusError as exception:
                            raise UrlFetchFailedError() from exception

                        self._validate_content_type(response)
                        html_bytes = self._read_limited_body(response)
                        return self._extract_text(html_bytes, response.encoding)
                    finally:
                        response.close()
                except httpx.TimeoutException as exception:
                    raise UrlFetchTimeoutError() from exception
                except httpx.RequestError as exception:
                    raise UrlFetchFailedError() from exception

        raise UrlFetchFailedError()

    def _prepare_safe_target(self, url: str) -> _SafeRequestTarget:
        try:
            logical_url = httpx.URL(url)
        except (TypeError, ValueError, httpx.InvalidURL) as exception:
            raise UrlInvalidError() from exception

        if logical_url.scheme not in {"http", "https"}:
            raise UrlInvalidError()
        if not logical_url.host or logical_url.userinfo:
            raise UrlInvalidError()

        hostname = logical_url.host.rstrip(".").casefold()
        if not hostname or hostname == "localhost" or hostname.endswith(".localhost"):
            raise UrlBlockedError()

        port = logical_url.port or (443 if logical_url.scheme == "https" else 80)
        addresses = self._resolve_addresses(hostname, port)
        pinned_address = addresses[0]

        # URL 使用已校验 IP 建立连接，同时保留原 Host 与 HTTPS SNI/证书校验名称。
        pinned_url = logical_url.copy_with(
            host=pinned_address.compressed,
            fragment=None,
        )
        sni_hostname = hostname if logical_url.scheme == "https" else None
        return _SafeRequestTarget(
            logical_url=logical_url.copy_with(fragment=None),
            pinned_url=pinned_url,
            host_header=logical_url.netloc.decode("ascii"),
            sni_hostname=sni_hostname,
        )

    def _resolve_addresses(
        self,
        hostname: str,
        port: int,
    ) -> list[ipaddress.IPv4Address | ipaddress.IPv6Address]:
        try:
            direct_address = ipaddress.ip_address(hostname)
        except ValueError:
            try:
                raw_addresses = self._resolver(hostname, port)
            except (OSError, UnicodeError, ValueError) as exception:
                raise UrlFetchFailedError() from exception
        else:
            raw_addresses = [direct_address.compressed]

        addresses: list[ipaddress.IPv4Address | ipaddress.IPv6Address] = []
        seen: set[str] = set()
        for raw_address in raw_addresses:
            try:
                address = ipaddress.ip_address(raw_address)
            except ValueError as exception:
                raise UrlFetchFailedError() from exception

            # IPv4-mapped IPv6 仍按其中的 IPv4 地址判断，防止 ::ffff:127.0.0.1 绕过。
            checked_address = (
                address.ipv4_mapped or address
                if isinstance(address, ipaddress.IPv6Address)
                else address
            )
            is_site_local = (
                isinstance(checked_address, ipaddress.IPv6Address)
                and checked_address.is_site_local
            )
            # is_global 并不会排除所有组播和已废弃的 IPv6 site-local 地址，
            # 因此显式拒绝它们，只允许真正的公网单播目标。
            if (
                not checked_address.is_global
                or checked_address.is_multicast
                or is_site_local
            ):
                raise UrlBlockedError()
            if address.compressed not in seen:
                seen.add(address.compressed)
                addresses.append(address)

        if not addresses:
            raise UrlFetchFailedError()
        return addresses

    @staticmethod
    def _validate_content_type(response: httpx.Response) -> None:
        content_type = response.headers.get("content-type", "")
        media_type = content_type.partition(";")[0].strip().casefold()
        if media_type not in ALLOWED_HTML_CONTENT_TYPES:
            raise UrlContentTypeUnsupportedError()

    @staticmethod
    def _read_limited_body(response: httpx.Response) -> bytes:
        content_length = response.headers.get("content-length")
        if content_length:
            try:
                declared_length = int(content_length)
            except ValueError as exception:
                raise UrlFetchFailedError() from exception
            if declared_length < 0:
                raise UrlFetchFailedError()
            if declared_length > MAX_URL_RESPONSE_BYTES:
                raise UrlResponseTooLargeError()

        chunks: list[bytes] = []
        downloaded_bytes = 0
        for chunk in response.iter_bytes(chunk_size=64 * 1_024):
            downloaded_bytes += len(chunk)
            if downloaded_bytes > MAX_URL_RESPONSE_BYTES:
                raise UrlResponseTooLargeError()
            chunks.append(chunk)
        return b"".join(chunks)

    @staticmethod
    def _extract_text(html_bytes: bytes, response_encoding: str | None) -> ExtractedUrlContent:
        try:
            soup = BeautifulSoup(
                html_bytes,
                "html.parser",
                from_encoding=response_encoding,
            )
        except (LookupError, UnicodeError) as exception:
            raise UrlContentEmptyError() from exception

        title = _normalize_visible_text(soup.title) if soup.title else ""

        # title 已单独保存，head 中其余元数据不属于正文；移除后再做 body 兜底。
        if soup.head:
            soup.head.decompose()
        for tag in soup.find_all(_REMOVED_TAG_NAMES):
            tag.decompose()
        for tag in list(soup.find_all(True)):
            if not isinstance(tag, Tag) or tag.parent is None:
                continue
            role = str(tag.get("role", "")).casefold()
            classes = " ".join(str(value) for value in tag.get("class", []))
            element_name = f"{tag.get('id', '')} {classes}"
            if role in _REMOVED_ROLES or _NOISE_NAME_PATTERN.search(element_name):
                tag.decompose()

        text = ""
        for tag_name in ("article", "main"):
            for candidate in soup.find_all(tag_name):
                text = _normalize_visible_text(candidate)
                if text:
                    break
            if text:
                break
        if not text:
            text = _normalize_visible_text(soup.body or soup)
        if not text:
            raise UrlContentEmptyError()

        normalized_text = text[:MAX_ANALYZE_INPUT_CHARS].rstrip()
        if not normalized_text:
            raise UrlContentEmptyError()
        normalized_title = title[:255].rstrip() or None
        return ExtractedUrlContent(title=normalized_title, text=normalized_text)


def _resolve_host_addresses(hostname: str, port: int) -> list[str]:
    try:
        address_info = socket.getaddrinfo(
            hostname,
            port,
            type=socket.SOCK_STREAM,
        )
    except socket.gaierror as exception:
        raise UrlFetchFailedError() from exception
    return [item[4][0] for item in address_info]


def _normalize_visible_text(tag: Tag) -> str:
    parts = [" ".join(part.split()) for part in tag.stripped_strings]
    return " ".join(part for part in parts if part).strip()
