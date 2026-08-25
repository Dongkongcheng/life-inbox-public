package com.lifeinbox.server.client;

import com.lifeinbox.server.dto.AiAnalyzeResponse;
import com.lifeinbox.server.dto.AiActionExtractionResponse;
import com.lifeinbox.server.dto.AiEntityResponse;
import com.lifeinbox.server.dto.AiEmbeddingResponse;
import com.lifeinbox.server.dto.AiHealthResponse;
import com.lifeinbox.server.dto.AiPreparedContentResponse;
import com.lifeinbox.server.dto.AiRerankDocument;
import com.lifeinbox.server.dto.AiRerankResponse;
import com.lifeinbox.server.dto.AiSemanticSearchResponse;
import com.lifeinbox.server.dto.AiVectorDeleteResponse;
import com.lifeinbox.server.dto.AiVectorIndexResponse;
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
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiServiceClientTests {

    @Test
    void actionExtractionPostsPreparedTextAndParsesTask31Contract() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/action/extract", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] body = """
                    {
                      "hasAction":true,
                      "actions":[{
                        "actionType":"DEADLINE",
                        "title":"提交课程设计报告",
                        "deadlineText":"8月25日前",
                        "deadline":"2026-08-25",
                        "evidence":"8月25日前提交课程设计报告"
                      }]
                    }
                    """.strip().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiActionExtractionResponse response = clientFor(server).extractActions(
                    "标题：\n课程设计\n\n正文：\n8月25日前提交报告",
                    LocalDate.of(2026, 8, 24)
            );

            assertEquals(true, response.hasAction());
            assertEquals(1, response.actions().size());
            assertEquals("DEADLINE", response.actions().getFirst().actionType());
            assertEquals("2026-08-25", response.actions().getFirst().deadline());
            assertEquals(
                    "{\"text\":\"标题：\\n课程设计\\n\\n正文：\\n8月25日前提交报告\","
                            + "\"referenceDate\":\"2026-08-24\"}",
                    requestBody.get()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void actionExtractionConvertsUpstreamFailureWithoutLeakingResponse() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/action/extract", exchange -> {
            byte[] body = "{\"detail\":\"secret provider response\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceUnavailableException exception = assertThrows(
                    AiServiceUnavailableException.class,
                    () -> clientFor(server).extractActions(
                            "正文",
                            LocalDate.of(2026, 8, 24)
                    )
            );
            assertEquals("AI Action 提取服务暂不可用", exception.getMessage());
        } finally {
            server.stop(0);
        }
    }

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
                Duration.ofMillis(200),
                Duration.ofMillis(200)
        );

        assertThrows(AiServiceUnavailableException.class, client::health);
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

    @Test
    void prepareUrlReturnsExtractedTextWithoutAnAnalyzeResult() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prepare/url", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] body = "{\"title\":\"网页标题\",\"text\":\"网页提取正文\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiPreparedContentResponse response = clientFor(server).prepareUrl(
                    null,
                    "https://example.com/article"
            );

            assertEquals(new AiPreparedContentResponse("网页标题", "网页提取正文"), response);
            assertEquals(
                    "{\"title\":null,\"url\":\"https://example.com/article\"}",
                    requestBody.get()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void prepareFileAndImageReuseMultipartTransport() throws IOException {
        AtomicReference<String> fileBody = new AtomicReference<>();
        AtomicReference<String> imageBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prepare/file", exchange -> {
            fileBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.ISO_8859_1
            ));
            byte[] body = "{\"title\":\"notes.txt\",\"text\":\"TXT 正文\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/prepare/image", exchange -> {
            imageBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.ISO_8859_1
            ));
            byte[] body = "{\"title\":\"screen.png\",\"text\":\"OCR 正文\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = clientFor(server);
            AiPreparedContentResponse file = client.prepareFile(
                    "notes.txt",
                    namedTextResource(),
                    MediaType.TEXT_PLAIN
            );
            AiPreparedContentResponse image = client.prepareImage(
                    "screen.png",
                    namedImageResource(),
                    MediaType.IMAGE_PNG
            );

            assertEquals("TXT 正文", file.text());
            assertEquals("OCR 正文", image.text());
            assertEquals(true, fileBody.get().contains("name=\"file\""));
            assertEquals(true, imageBody.get().contains("name=\"file\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void embedPostsTextAndParsesValidatedVector() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embedding", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] body = """
                    {"model":"embedding-model","dimension":3,"embedding":[0.1,-0.2,0.3]}
                    """.strip().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiEmbeddingResponse result = clientFor(server).embed("Redis 分布式锁");

            assertEquals("embedding-model", result.model());
            assertEquals(3, result.dimension());
            assertEquals(List.of(0.1, -0.2, 0.3), result.embedding());
            assertEquals("{\"text\":\"Redis 分布式锁\"}", requestBody.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void embedRejectsMalformedModelDimensionAndValues() throws IOException {
        AtomicInteger requestIndex = new AtomicInteger();
        String[] responses = {
                "{\"model\":\" \" ,\"dimension\":1,\"embedding\":[0.1]}",
                "{\"model\":\"m\",\"dimension\":2,\"embedding\":[0.1]}",
                "{\"model\":\"m\",\"dimension\":1,\"embedding\":[null]}"
        };
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embedding", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = responses[requestIndex.getAndIncrement()]
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = clientFor(server);
            for (int index = 0; index < responses.length; index++) {
                assertThrows(AiServiceUnavailableException.class, () -> client.embed("正文"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void embedMapsFastApiFailureWithoutExposingResponseBody() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embedding", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"detail\":\"provider-secret-response\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceUnavailableException exception = assertThrows(
                    AiServiceUnavailableException.class,
                    () -> clientFor(server).embed("正文")
            );

            assertEquals("AI Embedding 服务暂不可用", exception.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void vectorIndexPostsCurrentItemTextAndParsesMetadata() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/vector/index", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] body = ("""
                    {"inboxItemId":123,"indexed":true,"collection":"items__model__d_3",\
                    "model":"embedding-model","dimension":3,"contentHash":"%s"}
                    """).formatted("a".repeat(64)).strip().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiVectorIndexResponse response = clientFor(server).indexVector(123L, "当前正文");

            assertEquals(true, response.indexed());
            assertEquals("embedding-model", response.model());
            assertEquals(3, response.dimension());
            assertEquals("{\"inboxItemId\":123,\"text\":\"当前正文\"}", requestBody.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void vectorIndexAcceptsExplicitDisabledSkipAndRejectsMalformedSuccess() throws IOException {
        AtomicInteger requestIndex = new AtomicInteger();
        String[] responses = {
                "{\"inboxItemId\":123,\"indexed\":false,\"collection\":null,\"model\":null,"
                        + "\"dimension\":null,\"contentHash\":null}",
                "{\"inboxItemId\":123,\"indexed\":true,\"collection\":\"items\","
                        + "\"model\":\"m\",\"dimension\":3,\"contentHash\":\"bad\"}"
        };
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/vector/index", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = responses[requestIndex.getAndIncrement()]
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = clientFor(server);
            assertEquals(false, client.indexVector(123L, "正文").indexed());
            assertThrows(
                    AiServiceUnavailableException.class,
                    () -> client.indexVector(123L, "正文")
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void vectorDeleteUsesIdempotentInternalDeleteEndpoint() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/vector/index/123", exchange -> {
            method.set(exchange.getRequestMethod());
            byte[] body = "{\"inboxItemId\":123,\"deleted\":true}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiVectorDeleteResponse response = clientFor(server).deleteVector(123L);

            assertEquals(true, response.deleted());
            assertEquals("DELETE", method.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void semanticSearchPostsBoundedQueryAndParsesOrderedCandidates() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/vector/search", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] body = """
                    {"results":[
                      {"inboxItemId":123,"score":0.91},
                      {"inboxItemId":456,"score":0.82}
                    ]}
                    """.strip().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiSemanticSearchResponse response = clientFor(server).searchVectors(
                    "防止接口重复请求",
                    40
            );

            assertEquals(List.of(123L, 456L), response.results().stream()
                    .map(candidate -> candidate.inboxItemId())
                    .toList());
            assertEquals(
                    "{\"query\":\"防止接口重复请求\",\"limit\":40}",
                    requestBody.get()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void semanticSearchRejectsMalformedResponseAndHidesUpstreamFailure() throws IOException {
        AtomicInteger requestIndex = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/vector/search", exchange -> {
            int index = requestIndex.getAndIncrement();
            exchange.getRequestBody().readAllBytes();
            String responseBody = index == 0
                    ? "{\"results\":[{\"inboxItemId\":123,\"score\":null}]}"
                    : "{\"detail\":\"secret qdrant response\"}";
            int status = index == 0 ? 200 : 503;
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = clientFor(server);
            AiServiceUnavailableException malformed = assertThrows(
                    AiServiceUnavailableException.class,
                    () -> client.searchVectors("正文", 20)
            );
            AiServiceUnavailableException unavailable = assertThrows(
                    AiServiceUnavailableException.class,
                    () -> client.searchVectors("正文", 20)
            );

            assertEquals(
                    "AI 服务返回了无效的 Semantic Search 结果",
                    malformed.getMessage()
            );
            assertEquals("AI Semantic Search 服务暂不可用", unavailable.getMessage());
            assertEquals(false, unavailable.getMessage().contains("secret qdrant response"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rerankPostsBoundedDocumentsAndAcceptsPartialKnownResults() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] body = """
                    {"results":[{"id":456,"score":0.93}]}
                    """.strip().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiRerankResponse response = clientFor(server).rerank(
                    "防止接口重复请求",
                    List.of(
                            new AiRerankDocument(123L, "标题：缓存雪崩"),
                            new AiRerankDocument(456L, "标题：接口幂等")
                    ),
                    2
            );

            assertEquals(List.of(456L), response.results().stream()
                    .map(candidate -> candidate.id())
                    .toList());
            assertEquals(
                    "{\"query\":\"防止接口重复请求\",\"documents\":["
                            + "{\"id\":123,\"text\":\"标题：缓存雪崩\"},"
                            + "{\"id\":456,\"text\":\"标题：接口幂等\"}],\"topK\":2}",
                    requestBody.get()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rerankRejectsUnknownDuplicateOrMalformedResultsWithoutLeakingBody()
            throws IOException {
        AtomicInteger requestIndex = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            int index = requestIndex.getAndIncrement();
            exchange.getRequestBody().readAllBytes();
            String responseBody = switch (index) {
                case 0 -> "{\"results\":[{\"id\":999,\"score\":0.9}]}";
                case 1 -> "{\"results\":[{\"id\":123,\"score\":0.9},"
                        + "{\"id\":123,\"score\":0.8}]}";
                default -> "{\"detail\":\"secret provider response\"}";
            };
            int status = index < 2 ? 200 : 503;
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            AiServiceClient client = clientFor(server);
            List<AiRerankDocument> documents = List.of(
                    new AiRerankDocument(123L, "正文")
            );

            AiServiceUnavailableException unknown = assertThrows(
                    AiServiceUnavailableException.class,
                    () -> client.rerank("query", documents, 1)
            );
            AiServiceUnavailableException duplicate = assertThrows(
                    AiServiceUnavailableException.class,
                    () -> client.rerank("query", documents, 1)
            );
            AiServiceUnavailableException unavailable = assertThrows(
                    AiServiceUnavailableException.class,
                    () -> client.rerank("query", documents, 1)
            );

            assertEquals("AI 服务返回了无效的 Rerank 结果", unknown.getMessage());
            assertEquals("AI 服务返回了无效的 Rerank 结果", duplicate.getMessage());
            assertEquals("AI Rerank 服务暂不可用", unavailable.getMessage());
            assertEquals(false, unavailable.getMessage().contains("secret provider response"));
        } finally {
            server.stop(0);
        }
    }

    private AiServiceClient clientFor(HttpServer server) {
        return new AiServiceClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(1),
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
