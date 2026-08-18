package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.AiAnalyzeResponse;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.mapper.InboxItemMapper;
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
 * 编排 TEXT 的统一 AI Analyze：一次 LLM 调用得到 Summary、Category 和 Tags。
 */
@Service
public class InboxAnalyzeService {

    private static final String TYPE_TEXT = "TEXT";
    private static final int MAX_INPUT_CHARS = 20_000;
    private static final int MAX_SUMMARY_CHARS = 2_000;
    private static final int MAX_TAGS = 5;
    private static final int MAX_TAG_CHARS = 64;
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

    private final InboxItemMapper inboxItemMapper;
    private final AiServiceClient aiServiceClient;
    private final InboxAnalysisPersistenceService persistenceService;

    public InboxAnalyzeService(
            InboxItemMapper inboxItemMapper,
            AiServiceClient aiServiceClient,
            InboxAnalysisPersistenceService persistenceService
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.aiServiceClient = aiServiceClient;
        this.persistenceService = persistenceService;
    }

    /**
     * LLM 调用刻意放在事务外；只有全部结果通过 Java 二次校验后才开启短数据库事务。
     */
    public InboxItem analyze(Long id) {
        InboxItem inboxItem = inboxItemMapper.selectById(id);
        validateInboxItem(inboxItem);

        AiAnalyzeResponse aiResponse = aiServiceClient.analyze(
                inboxItem.getTitle(),
                inboxItem.getContent()
        );
        ValidatedAnalysis analysis = validateAnalysis(aiResponse);

        return persistenceService.replaceAnalysis(
                id,
                analysis.summary(),
                analysis.category(),
                analysis.tags()
        );
    }

    private void validateInboxItem(InboxItem inboxItem) {
        if (inboxItem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
        if (!TYPE_TEXT.equals(inboxItem.getType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前只支持分析 TEXT");
        }
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
            String name = normalizeTagName(rawTag);
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

        return new ValidatedAnalysis(
                summary,
                category,
                new ArrayList<>(uniqueTags.values())
        );
    }

    private String normalizeTagName(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        return INTERNAL_WHITESPACE.matcher(normalized).replaceAll(" ");
    }

    private AiServiceUnavailableException invalidAnalysis() {
        return new AiServiceUnavailableException("AI 服务返回了无效分析结果");
    }

    private record ValidatedAnalysis(
            String summary,
            String category,
            List<NormalizedTag> tags
    ) {
    }
}
