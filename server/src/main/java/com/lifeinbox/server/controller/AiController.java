package com.lifeinbox.server.controller;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.AiHealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供经由 Spring Boot 访问内部 AI 服务的集成验证入口。 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiServiceClient aiServiceClient;

    public AiController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    /** 完整验证 Browser/API Client -> Java -> Python 的健康检查链路。 */
    @GetMapping("/health")
    public AiHealthResponse health() {
        return aiServiceClient.health();
    }
}
