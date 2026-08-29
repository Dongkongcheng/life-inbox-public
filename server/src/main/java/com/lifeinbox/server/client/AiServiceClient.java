package com.lifeinbox.server.client;

import com.lifeinbox.server.dto.AiHealthResponse;
import com.lifeinbox.server.dto.AiActionExtractionRequest;
import com.lifeinbox.server.dto.AiActionExtractionResponse;
import com.lifeinbox.server.dto.AiAnalyzeRequest;
import com.lifeinbox.server.dto.AiAnalyzeResponse;
import com.lifeinbox.server.dto.AiEmbeddingRequest;
import com.lifeinbox.server.dto.AiEmbeddingResponse;
import com.lifeinbox.server.dto.AiPreparedContentResponse;
import com.lifeinbox.server.dto.AiRelationDiscoveryItem;
import com.lifeinbox.server.dto.AiRelationDiscoveryRequest;
import com.lifeinbox.server.dto.AiRelationDiscoveryResponse;
import com.lifeinbox.server.dto.AiRerankCandidate;
import com.lifeinbox.server.dto.AiRerankDocument;
import com.lifeinbox.server.dto.AiRerankRequest;
import com.lifeinbox.server.dto.AiRerankResponse;
import com.lifeinbox.server.dto.AiSemanticSearchCandidate;
import com.lifeinbox.server.dto.AiSemanticSearchRequest;
import com.lifeinbox.server.dto.AiSemanticSearchResponse;
import com.lifeinbox.server.dto.AiUrlAnalyzeRequest;
import com.lifeinbox.server.dto.AiVectorDeleteResponse;
import com.lifeinbox.server.dto.AiVectorIndexRequest;
import com.lifeinbox.server.dto.AiVectorIndexResponse;
import com.lifeinbox.server.dto.AiVectorNeighborCandidate;
import com.lifeinbox.server.dto.AiVectorNeighborRequest;
import com.lifeinbox.server.dto.AiVectorNeighborResponse;
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
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 集中封装 Spring Boot 对 Python AI Service 的 HTTP 调用。
 * Controller 不关心底层连接方式，后续 AI 能力也可以继续复用同一服务边界。
 */
@Component
public class AiServiceClient {

    private static final String EXPECTED_STATUS = "ok";
    private static final String EXPECTED_SERVICE = "life-inbox-ai";
    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_VECTOR_NEIGHBOR_RESPONSE_SIZE = 100;

    private final RestClient healthRestClient;
    private final RestClient analysisRestClient;
    private final RestClient rerankRestClient;

    public AiServiceClient(
            @Value("${life-inbox.ai.base-url:http://localhost:8000}") String baseUrl,
            @Value("${life-inbox.ai.connect-timeout:2s}") Duration connectTimeout,
            @Value("${life-inbox.ai.read-timeout:5s}") Duration readTimeout,
            @Value("${life-inbox.ai.analysis-read-timeout:${life-inbox.ai.summary-read-timeout:30s}}")
            Duration analysisReadTimeout,
            @Value("${life-inbox.ai.rerank-read-timeout:10s}") Duration rerankReadTimeout
    ) {
        this.healthRestClient = createRestClient(baseUrl, connectTimeout, readTimeout);
        this.analysisRestClient = createRestClient(baseUrl, connectTimeout, analysisReadTimeout);
        this.rerankRestClient = createRestClient(baseUrl, connectTimeout, rerankReadTimeout);
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

    /** 调用 Task 31 Action Extraction；业务校验与 Candidate 持久化仍由 Java 负责。 */
    public AiActionExtractionResponse extractActions(String text, LocalDate referenceDate) {
        try {
            AiActionExtractionResponse response = analysisRestClient.post()
                    .uri("/action/extract")
                    .body(new AiActionExtractionRequest(text, referenceDate))
                    .retrieve()
                    .body(AiActionExtractionResponse.class);
            if (response == null) {
                throw new AiServiceUnavailableException("AI 服务没有返回 Action 提取结果");
            }
            return response;
        } catch (AiServiceUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // 这里只终止本次手动提取；旧 Candidate 和 InboxItem 都不会被修改。
            throw new AiServiceUnavailableException("AI Action 提取服务暂不可用", exception);
        }
    }

    /**
     * 调用 Python 的瞬时 Embedding 能力；生成时机与向量保存由后续索引生命周期统一决定。
     */
    public AiEmbeddingResponse embed(String text) {
        try {
            AiEmbeddingResponse response = analysisRestClient.post()
                    .uri("/embedding")
                    .body(new AiEmbeddingRequest(text))
                    .retrieve()
                    .body(AiEmbeddingResponse.class);
            if (!isValidEmbedding(response)) {
                throw new AiServiceUnavailableException("AI 服务返回了无效的 Embedding 结果");
            }
            return response;
        } catch (AiServiceUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // 不透传 Python 或 Provider 的响应正文，Embedding 故障也不影响既有产品流程。
            throw new AiServiceUnavailableException("AI Embedding 服务暂不可用", exception);
        }
    }

    /** 由 Python 统一执行 Embedding 与 Qdrant Upsert，Java 只负责业务生命周期触发。 */
    public AiVectorIndexResponse indexVector(Long inboxItemId, String text) {
        try {
            AiVectorIndexResponse response = analysisRestClient.post()
                    .uri("/vector/index")
                    .body(new AiVectorIndexRequest(inboxItemId, text))
                    .retrieve()
                    .body(AiVectorIndexResponse.class);
            if (!isValidVectorIndexResponse(inboxItemId, response)) {
                throw new AiServiceUnavailableException("AI 服务返回了无效的 Vector Index 结果");
            }
            return response;
        } catch (AiServiceUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // 索引只是派生检索增强；不透传 Python/Qdrant 内部错误或配置。
            throw new AiServiceUnavailableException("AI Vector Index 服务暂不可用", exception);
        }
    }

    /** 删除稳定 Point ID；Point 不存在或 Vector Store 关闭都由 Python 作为幂等结果处理。 */
    public AiVectorDeleteResponse deleteVector(Long inboxItemId) {
        try {
            AiVectorDeleteResponse response = analysisRestClient.delete()
                    .uri("/vector/index/{inboxItemId}", inboxItemId)
                    .retrieve()
                    .body(AiVectorDeleteResponse.class);
            if (response == null || !inboxItemId.equals(response.inboxItemId())) {
                throw new AiServiceUnavailableException("AI 服务返回了无效的 Vector Delete 结果");
            }
            return response;
        } catch (AiServiceUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new AiServiceUnavailableException("AI Vector Delete 服务暂不可用", exception);
        }
    }

    /** Query Embedding 与 Qdrant Search 均由 Python 执行，Java 只接收 ID/Score 候选。 */
    public AiSemanticSearchResponse searchVectors(String query, int limit) {
        try {
            AiSemanticSearchResponse response = analysisRestClient.post()
                    .uri("/vector/search")
                    .body(new AiSemanticSearchRequest(query, limit))
                    .retrieve()
                    .body(AiSemanticSearchResponse.class);
            if (!isValidSemanticSearchResponse(response)) {
                throw new AiServiceUnavailableException(
                        "AI 服务返回了无效的 Semantic Search 结果"
                );
            }
            return response;
        } catch (AiServiceUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // 不透传 Query、Vector、Provider 或 Qdrant 响应；Keyword Search 不经过此调用。
            throw new AiServiceUnavailableException("AI Semantic Search 服务暂不可用", exception);
        }
    }

    /** 复用已有 Source Point Vector；Python 不为 Relation Candidate 重新调用 Embedding。 */
    public AiVectorNeighborResponse findVectorNeighbors(Long inboxItemId, int limit) {
        try {
            AiVectorNeighborResponse response = analysisRestClient.post()
                    .uri("/vector/neighbors")
                    .body(new AiVectorNeighborRequest(inboxItemId, limit))
                    .retrieve()
                    .body(AiVectorNeighborResponse.class);
            if (!isValidVectorNeighborResponse(response)) {
                throw new AiServiceUnavailableException(
                        "AI 服务返回了无效的 Vector Neighbor 结果"
                );
            }
            return response;
        } catch (AiServiceUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // 邻居只是候选信号；不透传 Qdrant 地址、响应正文或内部配置。
            throw new AiServiceUnavailableException(
                    "AI Vector Neighbor 服务暂不可用",
                    exception
            );
        }
    }

    /** 单次批量调用 Relation LLM；响应 ID 必须全部来自本次发送的候选。 */
    public AiRelationDiscoveryResponse discoverRelations(
            AiRelationDiscoveryItem source,
            List<AiRelationDiscoveryItem> candidates
    ) {
        try {
            AiRelationDiscoveryResponse response = analysisRestClient.post()
                    .uri("/relation/discover")
                    .body(new AiRelationDiscoveryRequest(source, candidates))
                    .retrieve()
                    .body(AiRelationDiscoveryResponse.class);
            if (!isValidRelationDiscoveryResponse(source, candidates, response)) {
                throw new AiServiceUnavailableException(
                        "AI 服务返回了无效的 Relation Discovery 结果"
                );
            }
            return response;
        } catch (AiServiceUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // 不透传候选正文、完整 Prompt 或 Provider 原始响应。
            throw new AiServiceUnavailableException(
                    "AI Relation Discovery 服务暂不可用",
                    exception
            );
        }
    }

    /** Rerank 只发送 RRF 已召回的有限候选；任何非法响应都由 Hybrid 层整体回退。 */
    public AiRerankResponse rerank(
            String query,
            List<AiRerankDocument> documents,
            int topK
    ) {
        try {
            AiRerankResponse response = rerankRestClient.post()
                    .uri("/rerank")
                    .body(new AiRerankRequest(query, documents, topK))
                    .retrieve()
                    .body(AiRerankResponse.class);
            if (!isValidRerankResponse(documents, response)) {
                throw new AiServiceUnavailableException(
                        "AI 服务返回了无效的 Rerank 结果"
                );
            }
            return response;
        } catch (AiServiceUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // 不透传 Query、Candidate 文本、Provider 响应或凭据；上层保留原 RRF 顺序。
            throw new AiServiceUnavailableException("AI Rerank 服务暂不可用", exception);
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

    /** 只让 Python 安全提取网页正文；LLM Analyze 由 Java 在派生正文落库后单独调用。 */
    public AiPreparedContentResponse prepareUrl(String title, String url) {
        try {
            AiPreparedContentResponse response = analysisRestClient.post()
                    .uri("/prepare/url")
                    .body(new AiUrlAnalyzeRequest(title, url))
                    .retrieve()
                    .body(AiPreparedContentResponse.class);
            if (response == null) {
                throw new AiServiceUnavailableException("AI 服务没有返回 URL 内容准备结果");
            }
            return response;
        } catch (AiServiceUnavailableException | UrlAnalyzeException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            UrlAnalyzeException knownFailure = parseKnownUrlFailure(exception);
            if (knownFailure != null) {
                throw knownFailure;
            }
            throw new AiServiceUnavailableException("AI URL 内容准备服务暂不可用", exception);
        } catch (RestClientException exception) {
            throw new AiServiceUnavailableException("AI URL 内容准备服务暂不可用", exception);
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

    /** 将受管文件交给既有文档解析器，返回纯文本而不调用 LLM。 */
    public AiPreparedContentResponse prepareFile(
            String title,
            Resource file,
            MediaType contentType
    ) {
        MultiValueMap<String, Object> multipart = buildMultipart(title, file, contentType);

        try {
            AiPreparedContentResponse response = analysisRestClient.post()
                    .uri("/prepare/file")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipart)
                    .retrieve()
                    .body(AiPreparedContentResponse.class);
            if (response == null) {
                throw new AiServiceUnavailableException("AI 服务没有返回 FILE 内容准备结果");
            }
            return response;
        } catch (AiServiceUnavailableException | FileAnalyzeException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            FileAnalyzeException knownFailure = parseKnownFileFailure(exception);
            if (knownFailure != null) {
                throw knownFailure;
            }
            throw new AiServiceUnavailableException("AI FILE 内容准备服务暂不可用", exception);
        } catch (RestClientException exception) {
            throw new AiServiceUnavailableException("AI FILE 内容准备服务暂不可用", exception);
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

    /** 将受管图片交给既有 OCR，返回纯文本而不调用 LLM 或 Vision 模型。 */
    public AiPreparedContentResponse prepareImage(
            String title,
            Resource file,
            MediaType contentType
    ) {
        MultiValueMap<String, Object> multipart = buildMultipart(title, file, contentType);

        try {
            AiPreparedContentResponse response = analysisRestClient.post()
                    .uri("/prepare/image")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipart)
                    .retrieve()
                    .body(AiPreparedContentResponse.class);
            if (response == null) {
                throw new AiServiceUnavailableException("AI 服务没有返回 IMAGE 内容准备结果");
            }
            return response;
        } catch (AiServiceUnavailableException | ImageAnalyzeException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            ImageAnalyzeException knownFailure = parseKnownImageFailure(exception);
            if (knownFailure != null) {
                throw knownFailure;
            }
            throw new AiServiceUnavailableException("AI IMAGE 内容准备服务暂不可用", exception);
        } catch (RestClientException exception) {
            throw new AiServiceUnavailableException("AI IMAGE 内容准备服务暂不可用", exception);
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

    private boolean isValidEmbedding(AiEmbeddingResponse response) {
        if (response == null
                || response.model() == null
                || response.model().isBlank()
                || response.dimension() <= 0
                || response.embedding() == null
                || response.embedding().size() != response.dimension()) {
            return false;
        }
        return response.embedding().stream().allMatch(
                value -> value != null && Double.isFinite(value)
        );
    }

    private boolean isValidVectorIndexResponse(
            Long expectedInboxItemId,
            AiVectorIndexResponse response
    ) {
        if (response == null || !expectedInboxItemId.equals(response.inboxItemId())) {
            return false;
        }
        if (!response.indexed()) {
            return response.collection() == null
                    && response.model() == null
                    && response.dimension() == null
                    && response.contentHash() == null;
        }
        return response.collection() != null
                && !response.collection().isBlank()
                && response.model() != null
                && !response.model().isBlank()
                && response.dimension() != null
                && response.dimension() > 0
                && response.contentHash() != null
                && SHA_256_HEX.matcher(response.contentHash()).matches();
    }

    private boolean isValidSemanticSearchResponse(AiSemanticSearchResponse response) {
        if (response == null || response.results() == null) {
            return false;
        }
        for (AiSemanticSearchCandidate candidate : response.results()) {
            if (candidate == null
                    || candidate.inboxItemId() == null
                    || candidate.inboxItemId() <= 0
                    || candidate.score() == null
                    || !Double.isFinite(candidate.score())) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidVectorNeighborResponse(AiVectorNeighborResponse response) {
        if (response == null
                || response.sourceIndexed() == null
                || response.results() == null
                || response.results().size() > MAX_VECTOR_NEIGHBOR_RESPONSE_SIZE) {
            return false;
        }
        if (!response.sourceIndexed()) {
            return response.results().isEmpty();
        }

        Set<Long> returnedIds = new HashSet<>();
        for (AiVectorNeighborCandidate candidate : response.results()) {
            if (candidate == null
                    || candidate.inboxItemId() == null
                    || candidate.inboxItemId() <= 0
                    || candidate.score() == null
                    || !Double.isFinite(candidate.score())
                    || !returnedIds.add(candidate.inboxItemId())) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidRelationDiscoveryResponse(
            AiRelationDiscoveryItem source,
            List<AiRelationDiscoveryItem> candidates,
            AiRelationDiscoveryResponse response
    ) {
        if (source == null
                || source.inboxItemId() == null
                || source.inboxItemId() <= 0
                || source.text() == null
                || source.text().isBlank()
                || candidates == null
                || response == null
                || response.relatedTargetInboxItemIds() == null
                || response.relatedTargetInboxItemIds().size() > candidates.size()) {
            return false;
        }

        Set<Long> suppliedIds = new HashSet<>();
        for (AiRelationDiscoveryItem candidate : candidates) {
            if (candidate == null
                    || candidate.inboxItemId() == null
                    || candidate.inboxItemId() <= 0
                    || candidate.text() == null
                    || candidate.text().isBlank()
                    || source.inboxItemId().equals(candidate.inboxItemId())
                    || !suppliedIds.add(candidate.inboxItemId())) {
                return false;
            }
        }

        Set<Long> returnedIds = new HashSet<>();
        for (Long returnedId : response.relatedTargetInboxItemIds()) {
            if (returnedId == null
                    || returnedId <= 0
                    || source.inboxItemId().equals(returnedId)
                    || !suppliedIds.contains(returnedId)
                    || !returnedIds.add(returnedId)) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidRerankResponse(
            List<AiRerankDocument> documents,
            AiRerankResponse response
    ) {
        if (documents == null || response == null || response.results() == null) {
            return false;
        }

        Set<Long> expectedIds = new HashSet<>();
        for (AiRerankDocument document : documents) {
            if (document == null
                    || document.id() == null
                    || document.id() <= 0
                    || document.text() == null
                    || document.text().isBlank()
                    || !expectedIds.add(document.id())) {
                return false;
            }
        }

        Set<Long> returnedIds = new HashSet<>();
        for (AiRerankCandidate candidate : response.results()) {
            if (candidate == null
                    || candidate.id() == null
                    || !expectedIds.contains(candidate.id())
                    || !returnedIds.add(candidate.id())
                    || candidate.score() == null
                    || !Double.isFinite(candidate.score())) {
                return false;
            }
        }
        return true;
    }

    private UrlAnalyzeException parseKnownUrlFailure(RestClientResponseException exception) {
        try {
            AiContentErrorResponse errorResponse = exception.getResponseBodyAs(
                    AiContentErrorResponse.class
            );
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
            AiContentErrorResponse errorResponse = exception.getResponseBodyAs(
                    AiContentErrorResponse.class
            );
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
            AiContentErrorResponse errorResponse = exception.getResponseBodyAs(
                    AiContentErrorResponse.class
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

    /** 三类内容提取错误共享同一上游 JSON 结构，但该结构只属于本 HTTP Client。 */
    private record AiContentErrorResponse(String code, String detail) {
    }
}
