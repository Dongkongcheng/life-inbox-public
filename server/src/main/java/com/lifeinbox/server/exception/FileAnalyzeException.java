package com.lifeinbox.server.exception;

import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.Optional;

/**
 * FILE 读取与文档提取阶段的安全业务错误。
 * Python 错误只有 code 与 HTTP 状态同时匹配 allowlist 时才会映射到产品响应。
 */
public class FileAnalyzeException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    private FileAnalyzeException(Failure failure) {
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

    public static FileAnalyzeException fileNotFound() {
        return new FileAnalyzeException(Failure.FILE_NOT_FOUND);
    }

    public static FileAnalyzeException typeUnsupported() {
        return new FileAnalyzeException(Failure.FILE_TYPE_UNSUPPORTED);
    }

    public static FileAnalyzeException fileTooLarge() {
        return new FileAnalyzeException(Failure.FILE_TOO_LARGE);
    }

    public static FileAnalyzeException fileReadFailed() {
        return new FileAnalyzeException(Failure.FILE_READ_FAILED);
    }

    public static Optional<FileAnalyzeException> fromUpstream(String code, int upstreamStatus) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(Failure.values())
                .filter(failure -> failure.upstreamStatus != null
                        && failure.code.equals(code)
                        && failure.upstreamStatus.value() == upstreamStatus)
                .findFirst()
                .map(FileAnalyzeException::new);
    }

    private enum Failure {
        FILE_NOT_FOUND(
                "FILE_NOT_FOUND",
                null,
                HttpStatus.NOT_FOUND,
                "文件不存在"
        ),
        FILE_READ_FAILED(
                "FILE_READ_FAILED",
                null,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "文件读取失败"
        ),
        FILE_TYPE_UNSUPPORTED(
                "FILE_TYPE_UNSUPPORTED",
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "当前只支持 TXT、Markdown 和 PDF 文件分析"
        ),
        FILE_TOO_LARGE(
                "FILE_TOO_LARGE",
                HttpStatus.CONTENT_TOO_LARGE,
                HttpStatus.CONTENT_TOO_LARGE,
                "用于 AI 分析的文件不能超过 10MB"
        ),
        FILE_ENCODING_UNSUPPORTED(
                "FILE_ENCODING_UNSUPPORTED",
                HttpStatus.UNPROCESSABLE_CONTENT,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "文本文件必须使用 UTF-8 编码"
        ),
        FILE_CONTENT_EMPTY(
                "FILE_CONTENT_EMPTY",
                HttpStatus.UNPROCESSABLE_CONTENT,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "文档内容为空"
        ),
        FILE_PDF_ENCRYPTED(
                "FILE_PDF_ENCRYPTED",
                HttpStatus.UNPROCESSABLE_CONTENT,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "PDF 已加密，当前不支持密码保护文件"
        ),
        FILE_PDF_NO_TEXT(
                "FILE_PDF_NO_TEXT",
                HttpStatus.UNPROCESSABLE_CONTENT,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "无法从 PDF 提取有效文本，文件可能需要 OCR"
        ),
        FILE_DOCUMENT_TOO_LONG(
                "FILE_DOCUMENT_TOO_LONG",
                HttpStatus.CONTENT_TOO_LARGE,
                HttpStatus.CONTENT_TOO_LARGE,
                "文档过长，当前版本暂不支持分析"
        ),
        FILE_EXTRACTION_FAILED(
                "FILE_EXTRACTION_FAILED",
                HttpStatus.UNPROCESSABLE_CONTENT,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "文档解析失败"
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
