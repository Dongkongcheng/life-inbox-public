package com.lifeinbox.server.client;

import com.lifeinbox.server.dto.AiHealthResponse;
import com.lifeinbox.server.dto.AiSummaryResponse;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiServiceClientTests {

    @Test
    void healthReturnsStructuredPythonResponse() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"ok\",\"service\":\"life-inbox-ai\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = new AiServiceClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1)
            );

            assertEquals(
                    new AiHealthResponse("ok", "life-inbox-ai"),
                    client.health()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void healthFailsClearlyWhenPythonIsUnavailable() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }

        AiServiceClient client = new AiServiceClient(
                "http://127.0.0.1:" + unusedPort,
                Duration.ofMillis(200),
                Duration.ofMillis(200),
                Duration.ofMillis(200)
        );

        assertThrows(AiServiceUnavailableException.class, client::health);
    }

    @Test
    void summarizePostsStructuredRequestAndParsesResponse() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/summarize", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"summary\":\"结构化摘要\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = new AiServiceClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1)
            );

            assertEquals(
                    new AiSummaryResponse("结构化摘要"),
                    client.summarize("学习", "Spring AI 正文")
            );
            assertEquals(
                    "{\"title\":\"学习\",\"text\":\"Spring AI 正文\"}",
                    requestBody.get()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void summarizeConvertsPythonFailureToAiServiceException() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/summarize", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = new AiServiceClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1)
            );

            assertThrows(
                    AiServiceUnavailableException.class,
                    () -> client.summarize(null, "正文")
            );
        } finally {
            server.stop(0);
        }
    }
}
