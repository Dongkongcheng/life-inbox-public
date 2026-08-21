package com.lifeinbox.server.exception;

import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.Optional;

/**
 * IMAGE 文件读取与 OCR 阶段的安全业务错误。
 * Python 错误只有 code 与 HTTP 状态同时匹配 allowlist 时才会映射到产品响应。
 */
public class ImageAnalyzeException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    private ImageAnalyzeException(Failure failure) {
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

    public static ImageAnalyzeException imageNotFound() {
        return new ImageAnalyzeException(Failure.IMAGE_NOT_FOUND);
    }

    public static ImageAnalyzeException typeUnsupported() {
        return new ImageAnalyzeException(Failure.IMAGE_TYPE_UNSUPPORTED);
    }

    public static ImageAnalyzeException imageTooLarge() {
        return new ImageAnalyzeException(Failure.IMAGE_TOO_LARGE);
    }

    public static ImageAnalyzeException imageReadFailed() {
        return new ImageAnalyzeException(Failure.IMAGE_READ_FAILED);
    }

    public static Optional<ImageAnalyzeException> fromUpstream(String code, int upstreamStatus) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(Failure.values())
                .filter(failure -> failure.upstreamStatus != null
                        && failure.code.equals(code)
                        && failure.upstreamStatus.value() == upstreamStatus)
                .findFirst()
                .map(ImageAnalyzeException::new);
    }

    private enum Failure {
        IMAGE_NOT_FOUND(
                "IMAGE_NOT_FOUND",
                null,
                HttpStatus.NOT_FOUND,
                "图片文件不存在"
        ),
        IMAGE_READ_FAILED(
                "IMAGE_READ_FAILED",
                null,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "图片文件读取失败"
        ),
        IMAGE_TYPE_UNSUPPORTED(
                "IMAGE_TYPE_UNSUPPORTED",
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "当前只支持 JPG、PNG 和 WEBP 图片分析"
        ),
        IMAGE_TOO_LARGE(
                "IMAGE_TOO_LARGE",
                HttpStatus.CONTENT_TOO_LARGE,
                HttpStatus.CONTENT_TOO_LARGE,
                "用于 AI 分析的图片不能超过 10MB"
        ),
        IMAGE_DIMENSIONS_TOO_LARGE(
                "IMAGE_DIMENSIONS_TOO_LARGE",
                HttpStatus.CONTENT_TOO_LARGE,
                HttpStatus.CONTENT_TOO_LARGE,
                "图片分辨率过大，当前版本暂不支持分析"
        ),
        IMAGE_INVALID(
                "IMAGE_INVALID",
                HttpStatus.UNPROCESSABLE_CONTENT,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "图片文件损坏或内容无效"
        ),
        IMAGE_OCR_FAILED(
                "IMAGE_OCR_FAILED",
                HttpStatus.UNPROCESSABLE_CONTENT,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "图片文字识别失败"
        ),
        IMAGE_TEXT_EMPTY(
                "IMAGE_TEXT_EMPTY",
                HttpStatus.UNPROCESSABLE_CONTENT,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "当前图片未识别到足够的文字内容"
        ),
        IMAGE_TEXT_TOO_LONG(
                "IMAGE_TEXT_TOO_LONG",
                HttpStatus.CONTENT_TOO_LARGE,
                HttpStatus.CONTENT_TOO_LARGE,
                "识别出的文字过长，当前版本暂不支持分析"
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
