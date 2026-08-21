package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.AiAnalyzeResponse;
import com.lifeinbox.server.dto.AiEntityResponse;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.exception.FileAnalyzeException;
import com.lifeinbox.server.exception.ImageAnalyzeException;
import com.lifeinbox.server.exception.UrlAnalyzeException;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 编排 TEXT/URL/FILE/IMAGE 的统一 AI Analyze：内容准备方式不同，但共享结果校验和持久化。
 */
@Service
public class InboxAnalyzeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxAnalyzeService.class);

    private static final String TYPE_TEXT = "TEXT";
    private static final String TYPE_URL = "URL";
    private static final String TYPE_FILE = "FILE";
    private static final String TYPE_IMAGE = "IMAGE";
    private static final int MAX_INPUT_CHARS = 20_000;
    private static final int MAX_SUMMARY_CHARS = 2_000;
    private static final int MAX_TAGS = 5;
    private static final int MAX_TAG_CHARS = 64;
    private static final int MAX_KEYWORDS = 8;
    private static final int MAX_KEYWORD_CHARS = 64;
    private static final int MAX_ENTITIES = 10;
    private static final int MAX_ENTITY_NAME_CHARS = 128;
    private static final Pattern INTERNAL_WHITESPACE = Pattern.compile(
            "\\s+",
            Pattern.UNICODE_CHARACTER_CLASS
    );
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "技术学习",
            "学习成长",
            "工作",
            "求职",
            "生活",
            "财务",
            "想法",
            "资讯",
            "其他"
    );
    private static final Set<String> ALLOWED_ENTITY_TYPES = Set.of(
            "PERSON",
            "ORGANIZATION",
            "LOCATION",
            "TECHNOLOGY",
            "PRODUCT",
            "EVENT",
            "OTHER"
    );

    private final InboxItemMapper inboxItemMapper;
    private final AiServiceClient aiServiceClient;
    private final FileStorageService fileStorageService;
    private final InboxAnalysisStatusService statusService;
    private final InboxAnalysisPersistenceService persistenceService;

    public InboxAnalyzeService(
            InboxItemMapper inboxItemMapper,
            AiServiceClient aiServiceClient,
            FileStorageService fileStorageService,
            InboxAnalysisStatusService statusService,
            InboxAnalysisPersistenceService persistenceService
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.aiServiceClient = aiServiceClient;
        this.fileStorageService = fileStorageService;
        this.statusService = statusService;
        this.persistenceService = persistenceService;
    }

    /**
     * 基础校验后先用短事务领取唯一 Attempt，再在事务外等待 Python/LLM。
     * stale PROCESSING 可由新 Attempt 懒恢复；旧 Attempt 的成功和失败都没有回写资格。
     */
    public InboxItem analyze(Long id) {
        InboxItem inboxItem = inboxItemMapper.selectById(id);
        if (inboxItem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }

        validateAnalyzableItem(inboxItem);
        String attemptId = statusService.markProcessing(id);

        ValidatedAnalysis analysis;
        try {
            AiAnalyzeResponse aiResponse = requestAnalysis(inboxItem);
            analysis = validateAnalysis(aiResponse);
        } catch (RuntimeException exception) {
            recordFailure(id, attemptId, safeProcessingFailureMessage(exception), exception);
            throw exception;
        }

        try {
            // 持久化 Bean 在同一短事务内替换五类结果，并在最后设置 SUCCESS。
            return persistenceService.replaceAnalysis(
                    id,
                    attemptId,
                    analysis.summary(),
                    analysis.category(),
                    analysis.tags(),
                    analysis.keywords(),
                    analysis.entities()
            );
        } catch (RuntimeException exception) {
            recordFailure(id, attemptId, "AI 结果保存失败", exception);
            throw exception;
        }
    }

    private void validateAnalyzableItem(InboxItem inboxItem) {
        if (TYPE_TEXT.equals(inboxItem.getType())) {
            validateText(inboxItem);
            return;
        }
        if (TYPE_URL.equals(inboxItem.getType())) {
            if (inboxItem.getSourceUrl() == null || inboxItem.getSourceUrl().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL 的 sourceUrl 不能为空");
            }
            return;
        }
        if (TYPE_FILE.equals(inboxItem.getType()) || TYPE_IMAGE.equals(inboxItem.getType())) {
            if (inboxItem.getFileUrl() == null || inboxItem.getFileUrl().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件访问地址不能为空");
            }
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "当前只支持分析 TEXT、URL、FILE 或 IMAGE"
        );
    }

    private AiAnalyzeResponse requestAnalysis(InboxItem inboxItem) {
        if (TYPE_TEXT.equals(inboxItem.getType())) {
            return aiServiceClient.analyze(inboxItem.getTitle(), inboxItem.getContent());
        }
        if (TYPE_URL.equals(inboxItem.getType())) {
            // Java 不抓取网页正文；Python 完成 SSRF 校验、正文提取后再复用统一 Analyze。
            return aiServiceClient.analyzeUrl(
                    inboxItem.getTitle(),
                    inboxItem.getSourceUrl().trim()
            );
        }
        if (TYPE_FILE.equals(inboxItem.getType())) {
            // 文件仍由 Java 存储层管理；只发送安全读取的内容，不向 Python 暴露磁盘路径。
            FileStorageService.AnalyzableFile file = fileStorageService.loadForAnalysis(
                    inboxItem.getFileUrl()
            );
            return aiServiceClient.analyzeFile(
                    inboxItem.getTitle(),
                    file.resource(),
                    file.mediaType()
            );
        }
        if (TYPE_IMAGE.equals(inboxItem.getType())) {
            // OCR 属于 Python 内容理解职责；Java 仍只发送受管图片内容，不暴露磁盘路径。
            FileStorageService.AnalyzableFile image = fileStorageService.loadImageForAnalysis(
                    inboxItem.getFileUrl()
            );
            return aiServiceClient.analyzeImage(
                    inboxItem.getTitle(),
                    image.resource(),
                    image.mediaType()
            );
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "当前只支持分析 TEXT、URL、FILE 或 IMAGE"
        );
    }

    private String safeProcessingFailureMessage(RuntimeException exception) {
        if (exception instanceof UrlAnalyzeException
                || exception instanceof FileAnalyzeException
                || exception instanceof ImageAnalyzeException) {
            return exception.getMessage();
        }
        if (exception instanceof AiServiceUnavailableException) {
            return "AI 服务暂时不可用";
        }
        return "AI 分析失败";
    }

    private void recordFailure(
            Long inboxItemId,
            String attemptId,
            String safeMessage,
            RuntimeException originalException
    ) {
        try {
            boolean saved = statusService.markFailed(inboxItemId, attemptId, safeMessage);
            if (!saved) {
                LOGGER.info("忽略已失效 AI Attempt 的失败结果，InboxItem={}", inboxItemId);
            }
        } catch (RuntimeException statusException) {
            // 状态写入故障不能掩盖原始 Analyze 异常；只在服务日志记录，不写入数据库或 API。
            originalException.addSuppressed(statusException);
            LOGGER.warn("InboxItem {} 的 AI 失败状态保存失败", inboxItemId, statusException);
        }
    }

    private void validateText(InboxItem inboxItem) {
        if (inboxItem.getContent() == null || inboxItem.getContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TEXT 内容不能为空");
        }
        if (inboxItem.getContent().length() > MAX_INPUT_CHARS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "TEXT 内容不能超过 " + MAX_INPUT_CHARS + " 个字符"
            );
        }
    }

    private ValidatedAnalysis validateAnalysis(AiAnalyzeResponse response) {
        if (response == null) {
            throw invalidAnalysis();
        }

        String summary = response.summary() == null ? null : response.summary().trim();
        String category = response.category() == null ? null : response.category().trim();
        if (summary == null
                || summary.isBlank()
                || summary.length() > MAX_SUMMARY_CHARS
                || category == null
                || !ALLOWED_CATEGORIES.contains(category)) {
            throw invalidAnalysis();
        }

        List<String> rawTags = response.tags();
        if (rawTags == null || rawTags.isEmpty() || rawTags.size() > MAX_TAGS) {
            throw invalidAnalysis();
        }

        // LinkedHashMap 在大小写去重的同时保留模型给出的第一种展示形式和顺序。
        Map<String, NormalizedTag> uniqueTags = new LinkedHashMap<>();
        for (String rawTag : rawTags) {
            if (rawTag == null) {
                throw invalidAnalysis();
            }
            String name = normalizeResultText(rawTag);
            if (name.isBlank() || name.length() > MAX_TAG_CHARS) {
                throw invalidAnalysis();
            }
            String normalizedName = name.toLowerCase(Locale.ROOT);
            if (normalizedName.length() > MAX_TAG_CHARS) {
                throw invalidAnalysis();
            }
            uniqueTags.putIfAbsent(normalizedName, new NormalizedTag(name, normalizedName));
        }
        if (uniqueTags.isEmpty()) {
            throw invalidAnalysis();
        }

        List<String> rawKeywords = response.keywords();
        if (rawKeywords == null || rawKeywords.size() > MAX_KEYWORDS) {
            throw invalidAnalysis();
        }
        // Keyword 更贴近原文关键术语，不复用全局 Tag 字典；这里只做稳定的文本去重。
        Map<String, String> uniqueKeywords = new LinkedHashMap<>();
        for (String rawKeyword : rawKeywords) {
            if (rawKeyword == null) {
                throw invalidAnalysis();
            }
            String keyword = normalizeResultText(rawKeyword);
            if (keyword.isBlank() || keyword.length() > MAX_KEYWORD_CHARS) {
                throw invalidAnalysis();
            }
            uniqueKeywords.putIfAbsent(keyword.toLowerCase(Locale.ROOT), keyword);
        }

        List<AiEntityResponse> rawEntities = response.entities();
        if (rawEntities == null || rawEntities.size() > MAX_ENTITIES) {
            throw invalidAnalysis();
        }
        Map<String, NormalizedEntity> uniqueEntities = new LinkedHashMap<>();
        for (AiEntityResponse rawEntity : rawEntities) {
            if (rawEntity == null || rawEntity.name() == null || rawEntity.type() == null) {
                throw invalidAnalysis();
            }
            String name = normalizeResultText(rawEntity.name());
            String type = rawEntity.type().trim();
            if (name.isBlank()
                    || name.length() > MAX_ENTITY_NAME_CHARS
                    || !ALLOWED_ENTITY_TYPES.contains(type)) {
                throw invalidAnalysis();
            }
            // 同名但不同有限类型可能代表不同对象，因此使用 name + type 作为简单去重键。
            String uniqueKey = name.toLowerCase(Locale.ROOT) + "\u0000" + type;
            uniqueEntities.putIfAbsent(uniqueKey, new NormalizedEntity(name, type));
        }

        return new ValidatedAnalysis(
                summary,
                category,
                new ArrayList<>(uniqueTags.values()),
                new ArrayList<>(uniqueKeywords.values()),
                new ArrayList<>(uniqueEntities.values())
        );
    }

    private String normalizeResultText(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        return INTERNAL_WHITESPACE.matcher(normalized).replaceAll(" ");
    }

    private AiServiceUnavailableException invalidAnalysis() {
        return new AiServiceUnavailableException("AI 服务返回了无效分析结果");
    }

    private record ValidatedAnalysis(
            String summary,
            String category,
            List<NormalizedTag> tags,
            List<String> keywords,
            List<NormalizedEntity> entities
    ) {
    }
}
