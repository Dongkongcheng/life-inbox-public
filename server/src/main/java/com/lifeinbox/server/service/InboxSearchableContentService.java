package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.AiProcessingStatus;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.event.InboxActionContentReadyEvent;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 统一定义 Retrieval 使用的 item-level 文本；它是可重建派生数据，不替代 InboxItem 原始内容。
 */
@Service
public class InboxSearchableContentService {

    public static final int MAX_SEARCHABLE_CONTENT_CHARS = 20_000;

    private static final String TYPE_TEXT = "TEXT";
    private static final Pattern INLINE_WHITESPACE = Pattern.compile("[\\p{Zs}\\t\\f\\u000B]+");

    private final InboxItemMapper inboxItemMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public InboxSearchableContentService(
            InboxItemMapper inboxItemMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.eventPublisher = eventPublisher;
    }

    InboxSearchableContentService(InboxItemMapper inboxItemMapper) {
        this(inboxItemMapper, event -> {
        });
    }

    /** TEXT 的 content 就是业务源数据，规范化后直接消费，不额外复制到派生列。 */
    public String prepareText(String content) {
        String normalized = normalize(content);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TEXT 内容不能为空");
        }
        if (normalized.length() > MAX_SEARCHABLE_CONTENT_CHARS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "TEXT 内容不能超过 " + MAX_SEARCHABLE_CONTENT_CHARS + " 个字符"
            );
        }
        return normalized;
    }

    /**
     * 提取成功后立即以当前 Attempt 做短写入；后续 LLM 失败不会清空这份已成功派生的正文。
     */
    @Transactional
    public String replaceExtractedContent(Long inboxItemId, String attemptId, String content) {
        String normalized = normalize(content);
        if (normalized == null || normalized.length() > MAX_SEARCHABLE_CONTENT_CHARS) {
            throw new AiServiceUnavailableException("AI 服务返回了无效的内容准备结果");
        }

        int updatedRows = inboxItemMapper.updateSearchableContent(
                inboxItemId,
                attemptId,
                AiProcessingStatus.PROCESSING,
                normalized
        );
        if (updatedRows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI 分析尝试已失效");
        }
        // 事件与正文更新共享事务，只有提交成功后 Action 后台监听器才会收到。
        eventPublisher.publishEvent(new InboxActionContentReadyEvent(inboxItemId));
        return normalized;
    }

    /** Retrieval 统一读取已准备的正文，不在查询时重新抓网页、解析文件或执行 OCR。 */
    public String resolveForRetrieval(InboxItem inboxItem) {
        if (inboxItem == null) {
            return null;
        }
        String source = TYPE_TEXT.equals(inboxItem.getType())
                ? inboxItem.getContent()
                : inboxItem.getSearchableContent();
        return normalize(source);
    }

    String normalize(String content) {
        if (content == null) {
            return null;
        }

        String canonical = Normalizer.normalize(content, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        StringBuilder safeText = new StringBuilder(canonical.length());
        for (int index = 0; index < canonical.length(); index++) {
            char character = canonical.charAt(index);
            if (Character.isISOControl(character) && character != '\n' && character != '\t') {
                safeText.append(' ');
            } else {
                safeText.append(character);
            }
        }

        List<String> lines = new ArrayList<>();
        boolean previousBlank = true;
        for (String rawLine : safeText.toString().split("\n", -1)) {
            String line = INLINE_WHITESPACE.matcher(rawLine).replaceAll(" ").strip();
            if (!line.isEmpty()) {
                lines.add(line);
                previousBlank = false;
            } else if (!previousBlank) {
                lines.add("");
                previousBlank = true;
            }
        }

        String normalized = String.join("\n", lines).strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
