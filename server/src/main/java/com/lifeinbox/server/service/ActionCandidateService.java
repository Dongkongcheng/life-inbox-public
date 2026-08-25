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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 手动与自动 Action 提取共用的统一编排；两种入口只在线程与响应方式上不同。 */
@Service
public class ActionCandidateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActionCandidateService.class);

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
    private final ActionProcessingStatusService statusService;

    public ActionCandidateService(
            InboxItemMapper inboxItemMapper,
            ActionCandidateMapper actionCandidateMapper,
            InboxSearchableContentService searchableContentService,
            AiServiceClient aiServiceClient,
            ActionCandidatePersistenceService persistenceService,
            ActionProcessingStatusService statusService
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.actionCandidateMapper = actionCandidateMapper;
        this.searchableContentService = searchableContentService;
        this.aiServiceClient = aiServiceClient;
        this.persistenceService = persistenceService;
        this.statusService = statusService;
    }

    public List<ActionCandidateResponse> extract(Long inboxItemId) {
        requireActiveItem(inboxItemId);
        String attemptId = statusService.markProcessing(inboxItemId);

        List<ValidatedActionCandidate> candidates;
        try {
            // Claim 后重新读取，避免后台任务消费事件发布前的旧实体快照。
            InboxItem inboxItem = requireActiveItem(inboxItemId);
            LocalDate referenceDate = requireReferenceDate(inboxItem);
            String sourceText = buildSourceText(inboxItem);

            // Claim 已在短事务提交；外部 LLM 调用不持有数据库事务或行锁。
            AiActionExtractionResponse aiResponse = aiServiceClient.extractActions(
                    sourceText,
                    referenceDate
            );
            candidates = validate(aiResponse);
        } catch (RuntimeException exception) {
            recordFailure(inboxItemId, attemptId, safeFailureMessage(exception), exception);
            throw exception;
        }

        try {
            // 当前 Attempt 的 Candidate Replacement 与 SUCCESS 在同一短事务原子提交。
            return persistenceService.completeSuccess(inboxItemId, attemptId, candidates);
        } catch (RuntimeException exception) {
            recordFailure(inboxItemId, attemptId, "Action 结果保存失败", exception);
            throw exception;
        }
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

    private LocalDate requireReferenceDate(InboxItem inboxItem) {
        if (inboxItem.getCreatedTime() == null) {
            // 相对日期必须绑定 Source 的稳定创建日期，缺失时不能用服务器当前日期猜测。
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "InboxItem 缺少稳定的创建日期"
            );
        }
        return inboxItem.getCreatedTime().toLocalDate();
    }

    private String safeFailureMessage(RuntimeException exception) {
        if (exception instanceof AiServiceUnavailableException) {
            return "Action AI 服务暂时不可用";
        }
        if (exception instanceof ResponseStatusException responseException
                && responseException.getReason() != null) {
            return responseException.getReason();
        }
        return "Action 提取失败";
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
                LOGGER.info("忽略已失效 Action Attempt 的失败结果，InboxItem={}", inboxItemId);
            }
        } catch (RuntimeException statusException) {
            originalException.addSuppressed(statusException);
            LOGGER.warn("InboxItem {} 的 Action 失败状态保存失败", inboxItemId, statusException);
        }
    }

    private String buildSourceText(InboxItem inboxItem) {
        // URL/FILE/IMAGE 复用既有 searchable_content，不在 Action 流程重复 Fetch、Parse 或 OCR。
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
