package com.lifeinbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.AiEntityResponse;
import com.lifeinbox.server.dto.AiRerankCandidate;
import com.lifeinbox.server.dto.AiRerankDocument;
import com.lifeinbox.server.dto.AiRerankResponse;
import com.lifeinbox.server.dto.AiSemanticSearchCandidate;
import com.lifeinbox.server.dto.AiSemanticSearchResponse;
import com.lifeinbox.server.dto.CreateInboxItemRequest;
import com.lifeinbox.server.entity.InboxEntity;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxEntityMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import com.lifeinbox.server.mapper.InboxKeywordMapper;
import com.lifeinbox.server.mapper.InboxTagMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Inbox 核心业务入口：统一处理不同 Capture 类型，并保持 Controller 只负责 HTTP 协议转换。
 */
@Service
public class InboxService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxService.class);
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final String TYPE_TEXT = "TEXT";
    private static final String TYPE_URL = "URL";
    private static final String TYPE_FILE = "FILE";
    private static final String TYPE_IMAGE = "IMAGE";
    private static final String FILE_URL_PREFIX = "/api/files/";
    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_SEARCH_QUERY_LENGTH = 200;
    private static final int MAX_SEARCH_CATEGORY_LENGTH = 32;
    private static final String SEARCH_MODE_KEYWORD = "keyword";
    private static final String SEARCH_MODE_SEMANTIC = "semantic";
    private static final String SEARCH_MODE_HYBRID = "hybrid";
    private static final int DEFAULT_ADVANCED_RESULT_LIMIT = 20;
    private static final int MAX_ADVANCED_RESULT_LIMIT = 50;
    private static final int MAX_RETRIEVAL_CANDIDATE_LIMIT = 100;
    private static final Set<String> SEARCHABLE_TYPES = Set.of(
            TYPE_TEXT,
            TYPE_URL,
            TYPE_FILE,
            TYPE_IMAGE
    );

    private final InboxItemMapper inboxItemMapper;
    private final InboxTagMapper inboxTagMapper;
    private final InboxKeywordMapper inboxKeywordMapper;
    private final InboxEntityMapper inboxEntityMapper;
    private final UrlMetadataService urlMetadataService;
    private final FileStorageService fileStorageService;
    private final InboxAnalysisStatusService analysisStatusService;
    private final ActionProcessingStatusService actionStatusService;
    private final RelationProcessingStatusService relationStatusService;
    private final InboxCapturePersistenceService capturePersistenceService;
    private final InboxVectorIndexScheduler vectorIndexScheduler;
    private final AiServiceClient aiServiceClient;
    private final RerankDocumentBuilder rerankDocumentBuilder;
    private final boolean rerankEnabled;

    public InboxService(
            InboxItemMapper inboxItemMapper,
            InboxTagMapper inboxTagMapper,
            InboxKeywordMapper inboxKeywordMapper,
            InboxEntityMapper inboxEntityMapper,
            UrlMetadataService urlMetadataService,
            FileStorageService fileStorageService,
            InboxAnalysisStatusService analysisStatusService,
            ActionProcessingStatusService actionStatusService,
            RelationProcessingStatusService relationStatusService,
            InboxCapturePersistenceService capturePersistenceService,
            InboxVectorIndexScheduler vectorIndexScheduler,
            AiServiceClient aiServiceClient,
            RerankDocumentBuilder rerankDocumentBuilder,
            @Value("${life-inbox.ai.rerank-enabled:false}") boolean rerankEnabled
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.inboxTagMapper = inboxTagMapper;
        this.inboxKeywordMapper = inboxKeywordMapper;
        this.inboxEntityMapper = inboxEntityMapper;
        this.urlMetadataService = urlMetadataService;
        this.fileStorageService = fileStorageService;
        this.analysisStatusService = analysisStatusService;
        this.actionStatusService = actionStatusService;
        this.relationStatusService = relationStatusService;
        this.capturePersistenceService = capturePersistenceService;
        this.vectorIndexScheduler = vectorIndexScheduler;
        this.aiServiceClient = aiServiceClient;
        this.rerankDocumentBuilder = rerankDocumentBuilder;
        this.rerankEnabled = rerankEnabled;
    }

    public List<InboxItem> list() {
        // 归档只是修改状态而不是删除；主 Inbox 因此只查询 ACTIVE 数据。
        LambdaQueryWrapper<InboxItem> query = new LambdaQueryWrapper<>();
        query.eq(InboxItem::getStatus, STATUS_ACTIVE);
        return enrichItems(inboxItemMapper.selectList(query));
    }

    /** 默认 Keyword 只查 MySQL；Semantic/Hybrid 才请求派生候选，所有模式都不触发 AI Analyze。 */
    public List<InboxItem> search(String query) {
        return search(query, null, null, null, null, null);
    }

    public List<InboxItem> search(
            String query,
            String type,
            String category,
            Boolean favorite
    ) {
        return search(query, type, category, favorite, null, null);
    }

    public List<InboxItem> search(
            String query,
            String type,
            String category,
            Boolean favorite,
            String mode,
            Integer limit
    ) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索关键词不能为空");
        }
        if (normalizedQuery.length() > MAX_SEARCH_QUERY_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "搜索关键词长度不能超过 " + MAX_SEARCH_QUERY_LENGTH
            );
        }

        String normalizedType = normalizeOptionalFilter(type);
        if (normalizedType != null) {
            normalizedType = normalizedType.toUpperCase(Locale.ROOT);
            if (!SEARCHABLE_TYPES.contains(normalizedType)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的搜索类型");
            }
        }

        String normalizedCategory = normalizeOptionalFilter(category);
        if (normalizedCategory != null && normalizedCategory.length() > MAX_SEARCH_CATEGORY_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "搜索分类长度不能超过 " + MAX_SEARCH_CATEGORY_LENGTH
            );
        }

        Integer favoriteValue = favorite == null ? null : (favorite ? 1 : 0);
        String normalizedMode = normalizeOptionalFilter(mode);
        if (normalizedMode == null) {
            normalizedMode = SEARCH_MODE_KEYWORD;
        } else {
            normalizedMode = normalizedMode.toLowerCase(Locale.ROOT);
        }

        if (SEARCH_MODE_KEYWORD.equals(normalizedMode)) {
            // 默认路径保持 Task 21～23 行为，不调用 FastAPI 或依赖 Qdrant。
            return enrichItems(keywordRetrieve(
                    normalizedQuery,
                    normalizedType,
                    normalizedCategory,
                    favoriteValue,
                    null
            ));
        }
        if (!SEARCH_MODE_SEMANTIC.equals(normalizedMode)
                && !SEARCH_MODE_HYBRID.equals(normalizedMode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的搜索模式");
        }

        int resultLimit = normalizeAdvancedLimit(limit);
        if (SEARCH_MODE_SEMANTIC.equals(normalizedMode)) {
            int candidateLimit = retrievalCandidateLimit(resultLimit);
            return enrichItems(limitItems(semanticRetrieve(
                    normalizedQuery,
                    normalizedType,
                    normalizedCategory,
                    favoriteValue,
                    candidateLimit
            ), resultLimit));
        }
        return hybridSearch(
                normalizedQuery,
                normalizedType,
                normalizedCategory,
                favoriteValue,
                resultLimit
        );
    }

    private List<InboxItem> keywordRetrieve(
            String query,
            String type,
            String category,
            Integer favorite,
            Integer candidateLimit
    ) {
        return inboxItemMapper.searchActiveByKeyword(
                query,
                escapeLikeLiteral(query),
                type,
                category,
                favorite,
                candidateLimit
        );
    }

    private List<InboxItem> semanticRetrieve(
            String query,
            String type,
            String category,
            Integer favorite,
            int candidateLimit
    ) {
        AiSemanticSearchResponse semanticResponse = aiServiceClient.searchVectors(
                query,
                candidateLimit
        );

        // LinkedHashMap 同时保留 Qdrant 排序并防御性去重；Score 只存活于本次请求。
        Map<Long, Double> candidateScores = new LinkedHashMap<>();
        for (AiSemanticSearchCandidate candidate : semanticResponse.results()) {
            candidateScores.putIfAbsent(candidate.inboxItemId(), candidate.score());
            if (candidateScores.size() == candidateLimit) {
                break;
            }
        }
        if (candidateScores.isEmpty()) {
            return List.of();
        }

        List<InboxItem> authoritativeItems = inboxItemMapper.selectActiveByIdsAndFilters(
                new ArrayList<>(candidateScores.keySet()),
                type,
                category,
                favorite
        );
        Map<Long, InboxItem> itemsById = new HashMap<>();
        for (InboxItem item : authoritativeItems) {
            // Mapper 已限定 ACTIVE；这里再次守住业务边界，避免 stale Vector 影响产品结果。
            if (item.getId() != null && STATUS_ACTIVE.equals(item.getStatus())) {
                itemsById.put(item.getId(), item);
            }
        }

        List<InboxItem> orderedItems = new ArrayList<>();
        for (Long candidateId : candidateScores.keySet()) {
            InboxItem item = itemsById.get(candidateId);
            if (item != null) {
                orderedItems.add(item);
                if (orderedItems.size() == candidateLimit) {
                    break;
                }
            }
        }
        LOGGER.debug(
                "Semantic Search 完成，Candidate={}，MySQL Result={}",
                candidateScores.size(),
                orderedItems.size()
        );
        return orderedItems;
    }

    private List<InboxItem> hybridSearch(
            String query,
            String type,
            String category,
            Integer favorite,
            int resultLimit
    ) {
        int candidateLimit = retrievalCandidateLimit(resultLimit);
        List<InboxItem> keywordCandidates = null;
        List<InboxItem> semanticCandidates = null;

        try {
            keywordCandidates = keywordRetrieve(
                    query,
                    type,
                    category,
                    favorite,
                    candidateLimit
            );
        } catch (RuntimeException exception) {
            // Hybrid 的另一条分支仍可能提供有效结果；日志不记录完整 Query 或底层 Score。
            LOGGER.warn(
                    "Hybrid Search Keyword 分支不可用，尝试 Semantic-only 降级，Failure={}",
                    exception.getClass().getSimpleName()
            );
        }

        try {
            semanticCandidates = semanticRetrieve(
                    query,
                    type,
                    category,
                    favorite,
                    candidateLimit
            );
        } catch (RuntimeException exception) {
            // Vector Store 关闭、Embedding 或 Qdrant 故障时保留基础 Keyword 能力。
            LOGGER.warn(
                    "Hybrid Search Semantic 分支不可用，尝试 Keyword-only 降级，Failure={}",
                    exception.getClass().getSimpleName()
            );
        }

        if (keywordCandidates == null && semanticCandidates == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "搜索服务暂不可用"
            );
        }

        List<InboxItem> hybridCandidates;
        if (keywordCandidates == null) {
            hybridCandidates = limitItems(semanticCandidates, candidateLimit);
        } else if (semanticCandidates == null) {
            hybridCandidates = limitItems(keywordCandidates, candidateLimit);
        } else {
            // RRF 先保留有界候选池，Reranker 才有机会把候选池尾部的高相关条目提升。
            hybridCandidates = HybridSearchFusion.fuse(
                    keywordCandidates,
                    semanticCandidates,
                    candidateLimit
            );
        }
        List<InboxItem> rankedCandidates = rerankEnabled
                ? rerankCandidates(query, hybridCandidates)
                : hybridCandidates;
        List<InboxItem> hybridItems = limitItems(rankedCandidates, resultLimit);
        int mergedCandidateCount = mergedCandidateCount(
                keywordCandidates,
                semanticCandidates
        );

        LOGGER.debug(
                "Hybrid Search 完成，Keyword Candidate={}，Semantic Candidate={}，"
                        + "Merged Candidate={}，Final Result={}，Keyword Degraded={}，"
                        + "Semantic Degraded={}，Rerank Enabled={}",
                keywordCandidates == null ? 0 : keywordCandidates.size(),
                semanticCandidates == null ? 0 : semanticCandidates.size(),
                mergedCandidateCount,
                hybridItems.size(),
                keywordCandidates == null,
                semanticCandidates == null,
                rerankEnabled
        );
        return enrichItems(hybridItems);
    }

    private List<InboxItem> rerankCandidates(
            String query,
            List<InboxItem> hybridCandidates
    ) {
        if (hybridCandidates.isEmpty()) {
            return hybridCandidates;
        }

        try {
            List<AiRerankDocument> documents = rerankDocumentBuilder.build(hybridCandidates);
            if (documents.isEmpty()) {
                // 无文本候选仍按 RRF 返回，Rerank 不是产品搜索可用性的前置条件。
                LOGGER.debug(
                        "Hybrid Rerank 未执行，rerankApplied=false，fallback=hybrid_rrf，"
                                + "Reason=NoDocuments"
                );
                return hybridCandidates;
            }

            AiRerankResponse response = aiServiceClient.rerank(
                    query,
                    documents,
                    documents.size()
            );
            List<InboxItem> rerankedItems = applyRerankOrder(hybridCandidates, response);
            LOGGER.info(
                    "Hybrid Rerank 已应用，rerankApplied=true，Candidate={}，Document={}，Returned={}",
                    hybridCandidates.size(),
                    documents.size(),
                    response.results().size()
            );
            return rerankedItems;
        } catch (RuntimeException exception) {
            // Rerank 是最终排序增强；任何配置、超时或非法响应都完整保留原 RRF 顺序。
            LOGGER.warn(
                    "Hybrid Rerank 不可用，rerankApplied=false，fallback=hybrid_rrf，"
                            + "Candidate={}，Reason={}",
                    hybridCandidates.size(),
                    exception.getClass().getSimpleName()
            );
            return hybridCandidates;
        }
    }

    private List<InboxItem> applyRerankOrder(
            List<InboxItem> hybridCandidates,
            AiRerankResponse response
    ) {
        if (response == null || response.results() == null) {
            throw new IllegalArgumentException("Rerank response 不能为空");
        }

        Map<Long, RankedOriginalItem> originalItems = new LinkedHashMap<>();
        for (int index = 0; index < hybridCandidates.size(); index++) {
            InboxItem item = hybridCandidates.get(index);
            if (item == null || item.getId() == null || item.getId() <= 0) {
                throw new IllegalArgumentException("Hybrid Candidate ID 不合法");
            }
            if (originalItems.putIfAbsent(
                    item.getId(),
                    new RankedOriginalItem(item, index)
            ) != null) {
                throw new IllegalArgumentException("Hybrid Candidate ID 重复");
            }
        }

        Set<Long> rerankedIds = new HashSet<>();
        List<ScoredRerankItem> scoredItems = new ArrayList<>();
        for (AiRerankCandidate candidate : response.results()) {
            if (candidate == null
                    || candidate.id() == null
                    || candidate.score() == null
                    || !Double.isFinite(candidate.score())
                    || !rerankedIds.add(candidate.id())) {
                throw new IllegalArgumentException("Rerank Candidate 不合法");
            }

            RankedOriginalItem original = originalItems.get(candidate.id());
            if (original == null) {
                // Reranker 只能缩小或重排现有集合，绝不能通过未知 ID 扩大召回。
                throw new IllegalArgumentException("Rerank 返回了未知 Candidate ID");
            }
            scoredItems.add(new ScoredRerankItem(
                    original.item(),
                    candidate.score(),
                    original.rank()
            ));
        }

        scoredItems.sort(Comparator
                .comparingDouble(ScoredRerankItem::score).reversed()
                .thenComparingInt(ScoredRerankItem::originalRank));

        List<InboxItem> result = new ArrayList<>(hybridCandidates.size());
        scoredItems.stream().map(ScoredRerankItem::item).forEach(result::add);
        // Provider 少返回的候选不被静默丢弃，继续按原 RRF 顺序追加。
        for (InboxItem candidate : hybridCandidates) {
            if (!rerankedIds.contains(candidate.getId())) {
                result.add(candidate);
            }
        }
        return result;
    }

    private record RankedOriginalItem(InboxItem item, int rank) {
    }

    private record ScoredRerankItem(InboxItem item, double score, int originalRank) {
    }

    private int mergedCandidateCount(
            List<InboxItem> keywordCandidates,
            List<InboxItem> semanticCandidates
    ) {
        Set<Long> ids = new HashSet<>();
        if (keywordCandidates != null) {
            keywordCandidates.stream()
                    .map(InboxItem::getId)
                    .filter(id -> id != null)
                    .forEach(ids::add);
        }
        if (semanticCandidates != null) {
            semanticCandidates.stream()
                    .map(InboxItem::getId)
                    .filter(id -> id != null)
                    .forEach(ids::add);
        }
        return ids.size();
    }

    private int normalizeAdvancedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_ADVANCED_RESULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_ADVANCED_RESULT_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "语义或混合搜索 limit 必须在 1 到 " + MAX_ADVANCED_RESULT_LIMIT + " 之间"
            );
        }
        return limit;
    }

    private int retrievalCandidateLimit(int resultLimit) {
        return Math.min(MAX_RETRIEVAL_CANDIDATE_LIMIT, resultLimit * 2);
    }

    private List<InboxItem> limitItems(List<InboxItem> items, int limit) {
        if (items.size() <= limit) {
            return items;
        }
        return new ArrayList<>(items.subList(0, limit));
    }

    private String normalizeOptionalFilter(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String escapeLikeLiteral(String query) {
        // Mapper 固定使用 ! 作为 LIKE ESCAPE，依次转义它自身及两个通配符。
        return query.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private List<InboxItem> enrichItems(List<InboxItem> items) {
        // 当前数据量很小，逐条聚合三类分析子表；API 始终返回数组而不是数据库关系实体。
        for (InboxItem item : items) {
            item.setTags(inboxTagMapper.selectTagNamesByInboxItemId(item.getId()));
            item.setKeywords(inboxKeywordMapper.selectKeywordsByInboxItemId(item.getId()));
            item.setEntities(
                    inboxEntityMapper.selectEntitiesByInboxItemId(item.getId()).stream()
                            .map(this::toEntityResponse)
                            .toList()
            );
            item.setAiProcessingStale(analysisStatusService.isProcessingStale(item));
            item.setActionProcessingStale(actionStatusService.isProcessingStale(item));
            item.setRelationProcessingStale(relationStatusService.isProcessingStale(item));
        }
        return items;
    }

    private AiEntityResponse toEntityResponse(InboxEntity entity) {
        return new AiEntityResponse(entity.getName(), entity.getType());
    }

    public InboxItem create(CreateInboxItemRequest request) {
        InboxItem inboxItem = new InboxItem();
        inboxItem.setType(request.getType());
        inboxItem.setTitle(request.getTitle());

        if (TYPE_TEXT.equals(request.getType())) {
            if (isBlank(request.getContent())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TEXT 类型的 content 不能为空");
            }
            inboxItem.setContent(request.getContent());
        } else if (TYPE_URL.equals(request.getType())) {
            String sourceUrl = validateAndNormalizeUrl(request.getSourceUrl());
            inboxItem.setSourceUrl(sourceUrl);
            if (isBlank(request.getTitle())) {
                // 只有用户未填写标题时才抓取网页，避免覆盖用户主动输入的标题。
                inboxItem.setTitle(urlMetadataService.resolveTitle(sourceUrl));
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "暂不支持该 InboxItem 类型");
        }

        return capturePersistenceService.save(inboxItem);
    }

    public InboxItem createFile(MultipartFile file, String title) {
        FileStorageService.StoredFile storedFile = fileStorageService.store(file);
        return createStoredItem(storedFile, title, TYPE_FILE);
    }

    public InboxItem createImage(MultipartFile file, String title) {
        FileStorageService.StoredFile storedFile = fileStorageService.storeImage(file);
        return createStoredItem(storedFile, title, TYPE_IMAGE);
    }

    private InboxItem createStoredItem(
            FileStorageService.StoredFile storedFile,
            String title,
            String type
    ) {
        try {
            String normalizedTitle = title == null ? null : title.trim();
            if (normalizedTitle != null && normalizedTitle.length() > MAX_TITLE_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title 长度不能超过 255");
            }

            InboxItem inboxItem = new InboxItem();
            inboxItem.setType(type);
            inboxItem.setTitle(
                    isBlank(normalizedTitle)
                            ? defaultFileTitle(storedFile.originalFilename())
                            : normalizedTitle
            );
            inboxItem.setFileUrl(FILE_URL_PREFIX + storedFile.storedName());
            inboxItem.setStatus(STATUS_ACTIVE);
            inboxItem.setFavorite(0);

            return capturePersistenceService.save(inboxItem);
        } catch (RuntimeException | Error exception) {
            // 磁盘写入先于数据库 INSERT；后续任一步失败时删除文件，避免留下孤儿文件。
            fileStorageService.delete(storedFile.storedName());
            throw exception;
        }
    }

    private String defaultFileTitle(String originalFilename) {
        return originalFilename.length() <= MAX_TITLE_LENGTH
                ? originalFilename
                : originalFilename.substring(0, MAX_TITLE_LENGTH);
    }

    private String validateAndNormalizeUrl(String sourceUrl) {
        if (isBlank(sourceUrl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL 类型的 sourceUrl 不能为空");
        }

        String normalizedUrl = sourceUrl.trim();
        try {
            URI uri = new URI(normalizedUrl);
            String scheme = uri.getScheme();
            // 当前只允许可由 Metadata 服务安全处理的 HTTP(S) 地址。
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceUrl 必须是合法的 HTTP 或 HTTPS URL");
            }
            return normalizedUrl;
        } catch (URISyntaxException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "sourceUrl 必须是合法的 HTTP 或 HTTPS URL"
            );
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public void delete(Long id) {
        InboxItem inboxItem = inboxItemMapper.selectById(id);
        if (inboxItem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }

        int deletedRows = inboxItemMapper.deleteById(id);
        if (deletedRows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }

        // MySQL 删除已经成功；Qdrant 只做后台 best-effort 清理，失败不能恢复或阻止业务删除。
        vectorIndexScheduler.scheduleDelete(id);

        if ((TYPE_FILE.equals(inboxItem.getType()) || TYPE_IMAGE.equals(inboxItem.getType()))
                && !isBlank(inboxItem.getFileUrl())) {
            // 先确认数据库删除成功，再尽力清理磁盘；两种存储无法组成同一个原子事务。
            fileStorageService.deleteByFileUrl(inboxItem.getFileUrl());
        }
    }

    public void archive(Long id) {
        InboxItem inboxItem = inboxItemMapper.selectById(id);
        if (inboxItem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }

        // 只更新归档列，避免并发 Analyze 时用查询到的旧实体覆盖 AI 状态或结果。
        int updatedRows = inboxItemMapper.updateInboxStatus(id, STATUS_ARCHIVED);
        if (updatedRows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
        // 当前没有恢复 ACTIVE 的入口，归档后直接删除派生 Point，避免未来语义检索误召回。
        vectorIndexScheduler.scheduleDelete(id);
    }

    public void favorite(Long id) {
        updateFavorite(id, 1);
    }

    public void unfavorite(Long id) {
        updateFavorite(id, 0);
    }

    private void updateFavorite(Long id, int favorite) {
        InboxItem inboxItem = inboxItemMapper.selectById(id);
        if (inboxItem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }

        int updatedRows = inboxItemMapper.updateFavorite(id, favorite);
        if (updatedRows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
    }
}
