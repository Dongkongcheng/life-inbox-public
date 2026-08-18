package com.lifeinbox.server.client;

import com.lifeinbox.server.dto.AiHealthResponse;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * 集中封装 Spring Boot 对 Python AI Service 的 HTTP 调用。
 * Controller 不关心底层连接方式，后续 AI 能力也可以继续复用同一服务边界。
 */
@Component
public class AiServiceClient {

    private static final String EXPECTED_STATUS = "ok";
    private static final String EXPECTED_SERVICE = "life-inbox-ai";

    private final RestClient restClient;

    public AiServiceClient(
            @Value("${life-inbox.ai.base-url:http://localhost:8000}") String baseUrl,
            @Value("${life-inbox.ai.connect-timeout:2s}") Duration connectTimeout,
            @Value("${life-inbox.ai.read-timeout:5s}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        // 连接和读取都设置上限，避免 Python 停止或卡住时长期占用 Java 请求线程。
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        // Base URL 来自配置，开发、测试和部署环境可以指向不同的 AI 服务地址。
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /** 调用 Python 健康检查，并验证返回值确实符合双方约定的结构。 */
    public AiHealthResponse health() {
        try {
            AiHealthResponse response = restClient.get()
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
}
