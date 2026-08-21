package com.lifeinbox.server.client;

import com.lifeinbox.server.dto.AiAnalyzeResponse;
import com.lifeinbox.server.dto.AiEntityResponse;
import com.lifeinbox.server.dto.AiHealthResponse;
import com.lifeinbox.server.dto.AiSummaryResponse;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.exception.FileAnalyzeException;
import com.lifeinbox.server.exception.ImageAnalyzeException;
import com.lifeinbox.server.exception.UrlAnalyzeException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void analyzePostsOneStructuredRequestAndParsesAllResults() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("""
                    {
                      "summary":"结构化摘要",
                      "category":"技术学习",
                      "tags":["Java","Spring AI"],
                      "keywords":["ChatModel","Tool Calling"],
                      "entities":[
                        {"name":"Spring AI","type":"TECHNOLOGY"},
                        {"name":"OpenAI","type":"ORGANIZATION"}
                      ]
                    }
                    """).strip().getBytes(StandardCharsets.UTF_8);
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
                    new AiAnalyzeResponse(
                            "结构化摘要",
                            "技术学习",
                            List.of("Java", "Spring AI"),
                            List.of("ChatModel", "Tool Calling"),
                            List.of(
                                    new AiEntityResponse("Spring AI", "TECHNOLOGY"),
                                    new AiEntityResponse("OpenAI", "ORGANIZATION")
                            )
                    ),
                    client.analyze("学习", "Spring AI 正文")
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
    void analyzeConvertsPythonFailureToAiServiceException() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze", exchange -> {
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
                    () -> client.analyze(null, "正文")
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void analyzeUrlPostsExplicitRequestAndParsesSharedResult() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze/url", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = completeAnalysisJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = clientFor(server);

            AiAnalyzeResponse result = client.analyzeUrl(
                    "示例文章",
                    "https://example.com/article"
            );

            assertEquals("结构化摘要", result.summary());
            assertEquals(List.of("ChatModel", "Tool Calling"), result.keywords());
            assertEquals(
                    "{\"title\":\"示例文章\",\"url\":\"https://example.com/article\"}",
                    requestBody.get()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void analyzeUrlMapsOnlyKnownCodeAndExpectedUpstreamStatus() throws IOException {
        List<UrlFailureCase> cases = List.of(
                new UrlFailureCase("URL_INVALID", 400, HttpStatus.BAD_REQUEST, "URL 无效"),
                new UrlFailureCase("URL_BLOCKED", 403, HttpStatus.FORBIDDEN, "URL 被安全策略阻止"),
                new UrlFailureCase("URL_FETCH_TIMEOUT", 408, HttpStatus.GATEWAY_TIMEOUT, "网页读取超时"),
                new UrlFailureCase("URL_FETCH_FAILED", 424, HttpStatus.BAD_GATEWAY, "网页访问失败"),
                new UrlFailureCase(
                        "URL_CONTENT_TYPE_UNSUPPORTED",
                        415,
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "网页内容类型不受支持"
                ),
                new UrlFailureCase(
                        "URL_RESPONSE_TOO_LARGE",
                        413,
                        HttpStatus.CONTENT_TOO_LARGE,
                        "网页内容过大"
                ),
                new UrlFailureCase(
                        "URL_CONTENT_EMPTY",
                        422,
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "无法从网页提取有效正文"
                )
        );
        AtomicInteger requestIndex = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze/url", exchange -> {
            UrlFailureCase failure = cases.get(requestIndex.getAndIncrement());
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("""
                    {"code":"%s","detail":"不应透传的上游内部信息"}
                    """).formatted(failure.code()).strip().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(failure.upstreamStatus(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = clientFor(server);
            for (UrlFailureCase failure : cases) {
                UrlAnalyzeException exception = assertThrows(
                        UrlAnalyzeException.class,
                        () -> client.analyzeUrl(null, "https://example.com/article")
                );
                assertEquals(failure.code(), exception.getCode());
                assertEquals(failure.productStatus(), exception.getStatus());
                assertEquals(failure.safeDetail(), exception.getMessage());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void analyzeUrlKeepsUnknownMalformedAndLlmErrorsGeneric() throws IOException {
        AtomicInteger requestIndex = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze/url", exchange -> {
            int index = requestIndex.getAndIncrement();
            exchange.getRequestBody().readAllBytes();
            String responseBody;
            int status;
            if (index == 0) {
                responseBody = "{\"code\":\"UNKNOWN_URL_ERROR\",\"detail\":\"internal\"}";
                status = 400;
            } else if (index == 1) {
                responseBody = "not-json";
                status = 400;
            } else if (index == 2) {
                responseBody = "{\"code\":\"URL_BLOCKED\",\"detail\":\"wrong status\"}";
                status = 500;
            } else {
                responseBody = "{\"detail\":\"LLM 服务暂不可用\"}";
                status = 503;
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = clientFor(server);
            for (int index = 0; index < 4; index++) {
                assertThrows(
                        AiServiceUnavailableException.class,
                        () -> client.analyzeUrl(null, "https://example.com/article")
                );
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void analyzeFileSendsMultipartAndParsesCompleteResponse() throws IOException {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze/file", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body = completeAnalysisJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = clientFor(server);
            ByteArrayResource resource = new ByteArrayResource(
                    "FILE_CONTENT_MARKER".getBytes(StandardCharsets.UTF_8)
            ) {
                @Override
                public String getFilename() {
                    return "550e8400-e29b-41d4-a716-446655440000.txt";
                }
            };

            AiAnalyzeResponse response = client.analyzeFile(
                    "Architecture notes",
                    resource,
                    MediaType.TEXT_PLAIN
            );

            assertEquals("结构化摘要", response.summary());
            String multipartText = new String(requestBody.get(), StandardCharsets.UTF_8);
            assertEquals(true, contentType.get().startsWith("multipart/form-data;boundary="));
            assertEquals(true, multipartText.contains("name=\"file\""));
            assertEquals(true, multipartText.contains(
                    "filename=\"550e8400-e29b-41d4-a716-446655440000.txt\""
            ));
            assertEquals(true, multipartText.contains("Content-Type: text/plain"));
            assertEquals(true, multipartText.contains("FILE_CONTENT_MARKER"));
            assertEquals(true, multipartText.contains("name=\"title\""));
            assertEquals(true, multipartText.contains("Architecture notes"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void analyzeFileMapsOnlyKnownCodeAndExpectedUpstreamStatus() throws IOException {
        List<FileFailureCase> cases = List.of(
                new FileFailureCase("FILE_TYPE_UNSUPPORTED", 415, HttpStatus.UNSUPPORTED_MEDIA_TYPE),
                new FileFailureCase("FILE_TOO_LARGE", 413, HttpStatus.CONTENT_TOO_LARGE),
                new FileFailureCase("FILE_ENCODING_UNSUPPORTED", 422, HttpStatus.UNPROCESSABLE_CONTENT),
                new FileFailureCase("FILE_CONTENT_EMPTY", 422, HttpStatus.UNPROCESSABLE_CONTENT),
                new FileFailureCase("FILE_PDF_ENCRYPTED", 422, HttpStatus.UNPROCESSABLE_CONTENT),
                new FileFailureCase("FILE_PDF_NO_TEXT", 422, HttpStatus.UNPROCESSABLE_CONTENT),
                new FileFailureCase("FILE_DOCUMENT_TOO_LONG", 413, HttpStatus.CONTENT_TOO_LARGE),
                new FileFailureCase("FILE_EXTRACTION_FAILED", 422, HttpStatus.UNPROCESSABLE_CONTENT)
        );
        AtomicInteger requestIndex = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze/file", exchange -> {
            FileFailureCase failure = cases.get(requestIndex.getAndIncrement());
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("""
                    {"code":"%s","detail":"不应透传的内部信息"}
                    """).formatted(failure.code()).strip().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(failure.upstreamStatus(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = clientFor(server);
            ByteArrayResource resource = namedTextResource();
            for (FileFailureCase failure : cases) {
                FileAnalyzeException exception = assertThrows(
                        FileAnalyzeException.class,
                        () -> client.analyzeFile(null, resource, MediaType.TEXT_PLAIN)
                );
                assertEquals(failure.code(), exception.getCode());
                assertEquals(failure.productStatus(), exception.getStatus());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void analyzeFileKeepsUnknownAndLlmErrorsGeneric() throws IOException {
        AtomicInteger requestIndex = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze/file", exchange -> {
            int index = requestIndex.getAndIncrement();
            exchange.getRequestBody().readAllBytes();
            String responseBody = index == 0
                    ? "{\"code\":\"UNKNOWN_FILE_ERROR\",\"detail\":\"internal\"}"
                    : index == 1
                            ? "{\"code\":\"FILE_PDF_NO_TEXT\",\"detail\":\"wrong status\"}"
                            : "{\"detail\":\"LLM 服务暂不可用\"}";
            int status = index == 0 ? 422 : 503;
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = clientFor(server);
            for (int index = 0; index < 3; index++) {
                assertThrows(
                        AiServiceUnavailableException.class,
                        () -> client.analyzeFile(null, namedTextResource(), MediaType.TEXT_PLAIN)
                );
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void analyzeImageSendsMultipartAndParsesCompleteResponse() throws IOException {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze/image", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] body = completeAnalysisJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = clientFor(server);
            ByteArrayResource resource = namedImageResource();

            AiAnalyzeResponse response = client.analyzeImage(
                    "Course notice",
                    resource,
                    MediaType.IMAGE_PNG
            );

            assertEquals("结构化摘要", response.summary());
            String multipartText = new String(requestBody.get(), StandardCharsets.ISO_8859_1);
            assertEquals(true, contentType.get().startsWith("multipart/form-data;boundary="));
            assertEquals(true, multipartText.contains("name=\"file\""));
            assertEquals(true, multipartText.contains(
                    "filename=\"550e8400-e29b-41d4-a716-446655440000.png\""
            ));
            assertEquals(true, multipartText.contains("Content-Type: image/png"));
            assertEquals(true, multipartText.contains("IMAGE_CONTENT_MARKER"));
            assertEquals(true, multipartText.contains("Course notice"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void analyzeImageMapsOnlyKnownCodeAndExpectedUpstreamStatus() throws IOException {
        List<ImageFailureCase> cases = List.of(
                new ImageFailureCase("IMAGE_TYPE_UNSUPPORTED", 415, HttpStatus.UNSUPPORTED_MEDIA_TYPE),
                new ImageFailureCase("IMAGE_TOO_LARGE", 413, HttpStatus.CONTENT_TOO_LARGE),
                new ImageFailureCase("IMAGE_DIMENSIONS_TOO_LARGE", 413, HttpStatus.CONTENT_TOO_LARGE),
                new ImageFailureCase("IMAGE_INVALID", 422, HttpStatus.UNPROCESSABLE_CONTENT),
                new ImageFailureCase("IMAGE_OCR_FAILED", 422, HttpStatus.UNPROCESSABLE_CONTENT),
                new ImageFailureCase("IMAGE_TEXT_EMPTY", 422, HttpStatus.UNPROCESSABLE_CONTENT),
                new ImageFailureCase("IMAGE_TEXT_TOO_LONG", 413, HttpStatus.CONTENT_TOO_LARGE)
        );
        AtomicInteger requestIndex = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze/image", exchange -> {
            ImageFailureCase failure = cases.get(requestIndex.getAndIncrement());
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("""
                    {"code":"%s","detail":"不应透传的内部信息"}
                    """).formatted(failure.code()).strip().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(failure.upstreamStatus(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = clientFor(server);
            for (ImageFailureCase failure : cases) {
                ImageAnalyzeException exception = assertThrows(
                        ImageAnalyzeException.class,
                        () -> client.analyzeImage(null, namedImageResource(), MediaType.IMAGE_PNG)
                );
                assertEquals(failure.code(), exception.getCode());
                assertEquals(failure.productStatus(), exception.getStatus());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void analyzeImageKeepsUnknownAndLlmErrorsGeneric() throws IOException {
        AtomicInteger requestIndex = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analyze/image", exchange -> {
            int index = requestIndex.getAndIncrement();
            exchange.getRequestBody().readAllBytes();
            String responseBody = index == 0
                    ? "{\"code\":\"UNKNOWN_IMAGE_ERROR\",\"detail\":\"internal\"}"
                    : index == 1
                            ? "{\"code\":\"IMAGE_TEXT_EMPTY\",\"detail\":\"wrong status\"}"
                            : "{\"detail\":\"LLM 服务暂不可用\"}";
            int status = index == 0 ? 422 : 503;
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = clientFor(server);
            for (int index = 0; index < 3; index++) {
                assertThrows(
                        AiServiceUnavailableException.class,
                        () -> client.analyzeImage(
                                null,
                                namedImageResource(),
                                MediaType.IMAGE_PNG
                        )
                );
            }
        } finally {
            server.stop(0);
        }
    }

    private AiServiceClient clientFor(HttpServer server) {
        return new AiServiceClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
    }

    private ByteArrayResource namedTextResource() {
        return new ByteArrayResource("content".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "550e8400-e29b-41d4-a716-446655440000.txt";
            }
        };
    }

    private ByteArrayResource namedImageResource() {
        return new ByteArrayResource("IMAGE_CONTENT_MARKER".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "550e8400-e29b-41d4-a716-446655440000.png";
            }
        };
    }

    private String completeAnalysisJson() {
        return """
                {
                  "summary":"结构化摘要",
                  "category":"技术学习",
                  "tags":["Java","Spring AI"],
                  "keywords":["ChatModel","Tool Calling"],
                  "entities":[{"name":"Spring AI","type":"TECHNOLOGY"}]
                }
                """.strip();
    }

    private record UrlFailureCase(
            String code,
            int upstreamStatus,
            HttpStatus productStatus,
            String safeDetail
    ) {
    }

    private record FileFailureCase(
            String code,
            int upstreamStatus,
            HttpStatus productStatus
    ) {
    }

    private record ImageFailureCase(
            String code,
            int upstreamStatus,
            HttpStatus productStatus
    ) {
    }
}
