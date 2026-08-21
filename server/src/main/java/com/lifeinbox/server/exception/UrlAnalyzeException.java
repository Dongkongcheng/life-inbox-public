package com.lifeinbox.server.exception;

import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.Optional;

/**
 * URL 网页读取阶段的安全业务错误。
 * 只有 Python 返回约定 code 与约定 HTTP 状态的组合时，Java 才会创建这个异常。
 */
public class UrlAnalyzeException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    private UrlAnalyzeException(Failure failure) {
        super(failure.safeDetail);
        this.code = failure.code;
        this.status = failure.productStatus;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static Optional<UrlAnalyzeException> fromUpstream(String code, int upstreamStatus) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(Failure.values())
                .filter(failure -> failure.code.equals(code)
                        && failure.upstreamStatus.value() == upstreamStatus)
                .findFirst()
                .map(UrlAnalyzeException::new);
    }

    private enum Failure {
        URL_INVALID(
                "URL_INVALID",
                HttpStatus.BAD_REQUEST,
                HttpStatus.BAD_REQUEST,
                "URL 无效"
        ),
        URL_BLOCKED(
                "URL_BLOCKED",
                HttpStatus.FORBIDDEN,
                HttpStatus.FORBIDDEN,
                "URL 被安全策略阻止"
        ),
        URL_FETCH_TIMEOUT(
                "URL_FETCH_TIMEOUT",
                HttpStatus.REQUEST_TIMEOUT,
                HttpStatus.GATEWAY_TIMEOUT,
                "网页读取超时"
        ),
        URL_FETCH_FAILED(
                "URL_FETCH_FAILED",
                HttpStatus.FAILED_DEPENDENCY,
                HttpStatus.BAD_GATEWAY,
                "网页访问失败"
        ),
        URL_CONTENT_TYPE_UNSUPPORTED(
                "URL_CONTENT_TYPE_UNSUPPORTED",
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "网页内容类型不受支持"
        ),
        URL_RESPONSE_TOO_LARGE(
                "URL_RESPONSE_TOO_LARGE",
                HttpStatus.CONTENT_TOO_LARGE,
                HttpStatus.CONTENT_TOO_LARGE,
                "网页内容过大"
        ),
        URL_CONTENT_EMPTY(
                "URL_CONTENT_EMPTY",
                HttpStatus.UNPROCESSABLE_CONTENT,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "无法从网页提取有效正文"
        );

        private final String code;
        private final HttpStatus upstreamStatus;
        private final HttpStatus productStatus;
        private final String safeDetail;

        Failure(
                String code,
                HttpStatus upstreamStatus,
                HttpStatus productStatus,
                String safeDetail
        ) {
            this.code = code;
            this.upstreamStatus = upstreamStatus;
            this.productStatus = productStatus;
            this.safeDetail = safeDetail;
        }
    }
}
