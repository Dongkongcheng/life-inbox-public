package com.lifeinbox.server.client;

import com.lifeinbox.server.dto.AiHealthResponse;
import com.lifeinbox.server.dto.AiImageErrorResponse;
import com.lifeinbox.server.dto.AiAnalyzeRequest;
import com.lifeinbox.server.dto.AiAnalyzeResponse;
import com.lifeinbox.server.dto.AiFileErrorResponse;
import com.lifeinbox.server.dto.AiUrlAnalyzeRequest;
import com.lifeinbox.server.dto.AiUrlErrorResponse;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.exception.FileAnalyzeException;
import com.lifeinbox.server.exception.ImageAnalyzeException;
import com.lifeinbox.server.exception.UrlAnalyzeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Duration;

/**
 * 集中封装 Spring Boot 对 Python AI Service 的 HTTP 调用。
 * Controller 不关心底层连接方式，后续 AI 能力也可以继续复用同一服务边界。
 */
@Component
public class AiServiceClient {

    private static final String EXPECTED_STATUS = "ok";
    private static final String EXPECTED_SERVICE = "life-inbox-ai";

    private final RestClient healthRestClient;
    private final RestClient analysisRestClient;

    public AiServiceClient(
            @Value("${life-inbox.ai.base-url:http://localhost:8000}") String baseUrl,
            @Value("${life-inbox.ai.connect-timeout:2s}") Duration connectTimeout,
            @Value("${life-inbox.ai.read-timeout:5s}") Duration readTimeout,
            @Value("${life-inbox.ai.analysis-read-timeout:${life-inbox.ai.summary-read-timeout:30s}}")
            Duration analysisReadTimeout
    ) {
        this.healthRestClient = createRestClient(baseUrl, connectTimeout, readTimeout);
        this.analysisRestClient = createRestClient(baseUrl, connectTimeout, analysisReadTimeout);
    }

    private RestClient createRestClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        // 连接和读取都设置上限，避免 Python 停止或卡住时长期占用 Java 请求线程。
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        // Base URL 来自配置，开发、测试和部署环境可以指向不同的 AI 服务地址。
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /** 调用 Python 健康检查，并验证返回值确实符合双方约定的结构。 */
    public AiHealthResponse health() {
        try {
            AiHealthResponse response = healthRestClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(AiHealthResponse.class);

            if (response == null
                    || !EXPECTED_STATUS.equals(response.status())
                    || !EXPECTED_SERVICE.equals(response.service())) {
                throw new AiServiceUnavailableException("AI 服务返回了无效的健康状态");
            }
            return response;
        } catch (AiServiceUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // AI 只是增强能力；这里只转换当前健康请求的错误，不影响任何 Inbox Capture 服务。
            throw new AiServiceUnavailableException("AI 服务暂不可用", exception);
        }
    }

    /** 一次调用 Python Analyze API，获得完整的五类结构化理解结果。 */
    public AiAnalyzeResponse analyze(String title, String text) {
        try {
            AiAnalyzeResponse response = analysisRestClient.post()
                    .uri("/analyze")
                    .body(new AiAnalyzeRequest(title, text))
                    .retrieve()
                    .body(AiAnalyzeResponse.class);
            if (response == null) {
                throw new AiServiceUnavailableException("AI 服务没有返回分析结果");
            }
            return response;
        } catch (AiServiceUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // Python/LLM 失败只终止本次 Analyze，不会进入 Java 的持久化事务。
            throw new AiServiceUnavailableException("AI 分析服务暂不可用", exception);
        }
    }

    /** URL 正文由 Python 安全读取；Java 仍只接收与 TEXT 相同的 AnalyzeResult。 */
    public AiAnalyzeResponse analyzeUrl(String title, String url) {
        try {
            AiAnalyzeResponse response = analysisRestClient.post()
                    .uri("/analyze/url")
                    .body(new AiUrlAnalyzeRequest(title, url))
                    .retrieve()
                    .body(AiAnalyzeResponse.class);
            if (response == null) {
                throw new AiServiceUnavailableException("AI 服务没有返回 URL 分析结果");
            }
            return response;
        } catch (AiServiceUnavailableException | UrlAnalyzeException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            UrlAnalyzeException knownFailure = parseKnownUrlFailure(exception);
            if (knownFailure != null) {
                throw knownFailure;
            }
            // LLM 错误、未知 code 或畸形响应都不能伪装成受信任的网页读取错误。
            throw new AiServiceUnavailableException("AI URL 分析服务暂不可用", exception);
        } catch (RestClientException exception) {
            throw new AiServiceUnavailableException("AI URL 分析服务暂不可用", exception);
        }
    }

    /** 将 Java 安全读取的受管文件作为 multipart 内容发送给 Python。 */
    public AiAnalyzeResponse analyzeFile(String title, Resource file, MediaType contentType) {
        MultiValueMap<String, Object> multipart = buildMultipart(title, file, contentType);

        try {
            AiAnalyzeResponse response = analysisRestClient.post()
                    .uri("/analyze/file")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipart)
                    .retrieve()
                    .body(AiAnalyzeResponse.class);
            if (response == null) {
                throw new AiServiceUnavailableException("AI 服务没有返回 FILE 分析结果");
            }
            return response;
        } catch (AiServiceUnavailableException | FileAnalyzeException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            FileAnalyzeException knownFailure = parseKnownFileFailure(exception);
            if (knownFailure != null) {
                throw knownFailure;
            }
            // 未知文档错误和 LLM 错误不能伪装成受信任的文件解析失败。
            throw new AiServiceUnavailableException("AI FILE 分析服务暂不可用", exception);
        } catch (RestClientException exception) {
            throw new AiServiceUnavailableException("AI FILE 分析服务暂不可用", exception);
        }
    }

    /** 将 Java 安全读取的受管图片作为 multipart 内容发送给 Python OCR。 */
    public AiAnalyzeResponse analyzeImage(String title, Resource file, MediaType contentType) {
        MultiValueMap<String, Object> multipart = buildMultipart(title, file, contentType);

        try {
            AiAnalyzeResponse response = analysisRestClient.post()
                    .uri("/analyze/image")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipart)
                    .retrieve()
                    .body(AiAnalyzeResponse.class);
            if (response == null) {
                throw new AiServiceUnavailableException("AI 服务没有返回 IMAGE 分析结果");
            }
            return response;
        } catch (AiServiceUnavailableException | ImageAnalyzeException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            ImageAnalyzeException knownFailure = parseKnownImageFailure(exception);
            if (knownFailure != null) {
                throw knownFailure;
            }
            // 未知 OCR 错误和 LLM 错误不能伪装成受信任的图片读取失败。
            throw new AiServiceUnavailableException("AI IMAGE 分析服务暂不可用", exception);
        } catch (RestClientException exception) {
            throw new AiServiceUnavailableException("AI IMAGE 分析服务暂不可用", exception);
        }
    }

    private MultiValueMap<String, Object> buildMultipart(
            String title,
            Resource file,
            MediaType contentType
    ) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentDispositionFormData("file", file.getFilename());
        fileHeaders.setContentType(contentType);
        MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
        multipart.add("file", new HttpEntity<>(file, fileHeaders));
        if (title != null && !title.isBlank()) {
            multipart.add("title", title.trim());
        }
        return multipart;
    }

    private UrlAnalyzeException parseKnownUrlFailure(RestClientResponseException exception) {
        try {
            AiUrlErrorResponse errorResponse = exception.getResponseBodyAs(AiUrlErrorResponse.class);
            if (errorResponse == null) {
                return null;
            }
            return UrlAnalyzeException.fromUpstream(
                    errorResponse.code(),
                    exception.getStatusCode().value()
            ).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private FileAnalyzeException parseKnownFileFailure(RestClientResponseException exception) {
        try {
            AiFileErrorResponse errorResponse = exception.getResponseBodyAs(AiFileErrorResponse.class);
            if (errorResponse == null) {
                return null;
            }
            return FileAnalyzeException.fromUpstream(
                    errorResponse.code(),
                    exception.getStatusCode().value()
            ).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ImageAnalyzeException parseKnownImageFailure(RestClientResponseException exception) {
        try {
            AiImageErrorResponse errorResponse = exception.getResponseBodyAs(
                    AiImageErrorResponse.class
            );
            if (errorResponse == null) {
                return null;
            }
            return ImageAnalyzeException.fromUpstream(
                    errorResponse.code(),
                    exception.getStatusCode().value()
            ).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
