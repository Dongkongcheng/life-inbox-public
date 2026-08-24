package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.ActionCandidateResponse;
import com.lifeinbox.server.dto.AiActionCandidateResponse;
import com.lifeinbox.server.dto.AiActionExtractionResponse;
import com.lifeinbox.server.entity.ActionCandidateType;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.exception.AiServiceUnavailableException;
import com.lifeinbox.server.mapper.ActionCandidateMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 手动 Action 提取编排：准备 Source、调用 Task 31、校验后再交给短事务持久化。 */
@Service
public class ActionCandidateService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final int MAX_ACTIONS = 10;
    private static final int MAX_TITLE_CHARS = 200;
    private static final int MAX_DEADLINE_TEXT_CHARS = 100;
    private static final int MAX_EVIDENCE_CHARS = 500;
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Set<String> SUPPORTED_TYPES = Set.of("TEXT", "URL", "FILE", "IMAGE");

    private final InboxItemMapper inboxItemMapper;
    private final ActionCandidateMapper actionCandidateMapper;
    private final InboxSearchableContentService searchableContentService;
    private final AiServiceClient aiServiceClient;
    private final ActionCandidatePersistenceService persistenceService;

    public ActionCandidateService(
            InboxItemMapper inboxItemMapper,
            ActionCandidateMapper actionCandidateMapper,
            InboxSearchableContentService searchableContentService,
            AiServiceClient aiServiceClient,
            ActionCandidatePersistenceService persistenceService
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.actionCandidateMapper = actionCandidateMapper;
        this.searchableContentService = searchableContentService;
        this.aiServiceClient = aiServiceClient;
        this.persistenceService = persistenceService;
    }

    public List<ActionCandidateResponse> extract(Long inboxItemId) {
        InboxItem inboxItem = requireActiveItem(inboxItemId);
        String sourceText = buildSourceText(inboxItem);

        // 外部 LLM 调用可能耗时或失败，必须在任何数据库事务开始前完成。
        AiActionExtractionResponse aiResponse = aiServiceClient.extractActions(sourceText);
        List<ValidatedActionCandidate> candidates = validate(aiResponse);

        // 只有成功响应才会进入短事务；Provider 失败或非法响应会原样保留旧候选。
        return persistenceService.replacePending(inboxItemId, candidates);
    }

    public List<ActionCandidateResponse> list(Long inboxItemId) {
        requireActiveItem(inboxItemId);
        return ActionCandidatePersistenceService.mapCandidates(
                actionCandidateMapper.selectByInboxItemId(inboxItemId)
        );
    }

    private InboxItem requireActiveItem(Long inboxItemId) {
        InboxItem inboxItem = inboxItemMapper.selectById(inboxItemId);
        if (inboxItem == null || !STATUS_ACTIVE.equals(inboxItem.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
        if (!SUPPORTED_TYPES.contains(inboxItem.getType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "InboxItem 类型不支持 Action 提取");
        }
        return inboxItem;
    }

    private String buildSourceText(InboxItem inboxItem) {
        // URL/FILE/IMAGE 复用既有 searchable_content，不在手动提取时重复 Fetch、Parse 或 OCR。
        String primaryContent = searchableContentService.resolveForRetrieval(inboxItem);
        String title = searchableContentService.normalize(inboxItem.getTitle());
        if (title == null && primaryContent == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "InboxItem 没有可用于 Action 提取的内容");
        }

        String sourceText;
        if (title == null) {
            sourceText = primaryContent;
        } else if (primaryContent == null) {
            sourceText = "标题：\n" + title;
        } else {
            sourceText = "标题：\n" + title + "\n\n正文：\n" + primaryContent;
        }
        return truncateSafely(sourceText, InboxSearchableContentService.MAX_SEARCHABLE_CONTENT_CHARS);
    }

    private String truncateSafely(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        int end = maxChars;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end).stripTrailing();
    }

    private List<ValidatedActionCandidate> validate(AiActionExtractionResponse response) {
        if (response == null
                || response.hasAction() == null
                || response.actions() == null
                || response.actions().size() > MAX_ACTIONS
                || response.hasAction() != !response.actions().isEmpty()) {
            throw invalidResponse();
        }

        List<ValidatedActionCandidate> candidates = new ArrayList<>(response.actions().size());
        for (AiActionCandidateResponse action : response.actions()) {
            candidates.add(validateCandidate(action));
        }
        return List.copyOf(candidates);
    }

    private ValidatedActionCandidate validateCandidate(AiActionCandidateResponse action) {
        if (action == null) {
            throw invalidResponse();
        }

        ActionCandidateType type;
        try {
            type = ActionCandidateType.valueOf(action.actionType());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalidResponse();
        }

        String title = requiredBounded(action.title(), MAX_TITLE_CHARS);
        String deadlineText = optionalBounded(action.deadlineText(), MAX_DEADLINE_TEXT_CHARS);
        String evidence = requiredBounded(action.evidence(), MAX_EVIDENCE_CHARS);
        LocalDate deadline = parseDeadline(action.deadline());

        if ((type == ActionCandidateType.DEADLINE && deadlineText == null)
                || (type == ActionCandidateType.TODO
                && (deadlineText != null || deadline != null))) {
            throw invalidResponse();
        }
        return new ValidatedActionCandidate(type, title, deadlineText, deadline, evidence);
    }

    private String requiredBounded(String value, int maxChars) {
        String normalized = optionalBounded(value, maxChars);
        if (normalized == null) {
            throw invalidResponse();
        }
        return normalized;
    }

    private String optionalBounded(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxChars) {
            throw invalidResponse();
        }
        return normalized;
    }

    private LocalDate parseDeadline(String value) {
        if (value == null) {
            return null;
        }
        if (!ISO_DATE.matcher(value).matches()) {
            throw invalidResponse();
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalidResponse();
        }
    }

    private AiServiceUnavailableException invalidResponse() {
        return new AiServiceUnavailableException("AI 服务返回了无效的 Action 结果");
    }
}
