package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.ActionCandidateSourceResponse;
import com.lifeinbox.server.dto.InboxSourceSummaryResponse;
import com.lifeinbox.server.dto.TodoSourceResponse;
import com.lifeinbox.server.entity.ActionCandidate;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.Todo;
import com.lifeinbox.server.mapper.ActionCandidateMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;

/** 按需解析 Todo 的只读来源，不参与 Todo 列表和生命周期写入。 */
@Service
public class TodoSourceService {

    static final int MAX_PREVIEW_CHARS = InboxItemPreviewBuilder.MAX_PREVIEW_CHARS;
    private static final String FILE_URL_PREFIX = "/api/files/";
    private static final Logger LOGGER = LoggerFactory.getLogger(TodoSourceService.class);

    private final TodoService todoService;
    private final InboxItemMapper inboxItemMapper;
    private final ActionCandidateMapper actionCandidateMapper;

    public TodoSourceService(
            TodoService todoService,
            InboxItemMapper inboxItemMapper,
            ActionCandidateMapper actionCandidateMapper
    ) {
        this.todoService = todoService;
        this.inboxItemMapper = inboxItemMapper;
        this.actionCandidateMapper = actionCandidateMapper;
    }

    /**
     * 来源缺失是合法降级状态。这里不重新抓取、解析或调用 AI，也不修改 Todo/Candidate/InboxItem。
     */
    public TodoSourceResponse getSource(Long todoId) {
        Todo todo = todoService.get(todoId);
        InboxItem inboxItem = loadInboxItem(todo);
        ActionCandidate candidate = loadActionCandidate(todo);

        InboxSourceSummaryResponse inboxSummary = inboxItem == null
                ? null
                : toInboxSummary(inboxItem);
        ActionCandidateSourceResponse candidateSummary = candidate == null
                ? null
                : toCandidateSummary(candidate);

        return new TodoSourceResponse(
                todo.getId(),
                inboxSummary != null || candidateSummary != null,
                inboxSummary,
                candidateSummary
        );
    }

    private InboxItem loadInboxItem(Todo todo) {
        Long sourceId = todo.getSourceInboxItemId();
        if (sourceId == null) {
            return null;
        }
        // selectById 不附加 ACTIVE 条件，因此归档来源仍可作为只读上下文查看。
        InboxItem inboxItem = inboxItemMapper.selectById(sourceId);
        if (inboxItem == null) {
            LOGGER.warn("Todo source InboxItem is unavailable: todoId={}, inboxItemId={}",
                    todo.getId(), sourceId);
        }
        return inboxItem;
    }

    private ActionCandidate loadActionCandidate(Todo todo) {
        Long sourceId = todo.getSourceActionCandidateId();
        if (sourceId == null) {
            return null;
        }
        ActionCandidate candidate = actionCandidateMapper.selectById(sourceId);
        if (candidate == null) {
            LOGGER.warn("Todo source ActionCandidate is unavailable: todoId={}, candidateId={}",
                    todo.getId(), sourceId);
        }
        return candidate;
    }

    private InboxSourceSummaryResponse toInboxSummary(InboxItem inboxItem) {
        return new InboxSourceSummaryResponse(
                inboxItem.getId(),
                inboxItem.getType(),
                inboxItem.getTitle(),
                InboxItemPreviewBuilder.build(inboxItem),
                safeSourceUrl(inboxItem.getSourceUrl()),
                safeManagedFileUrl(inboxItem.getFileUrl()),
                inboxItem.getStatus(),
                inboxItem.getCreatedTime()
        );
    }

    private ActionCandidateSourceResponse toCandidateSummary(ActionCandidate candidate) {
        return new ActionCandidateSourceResponse(
                candidate.getId(),
                candidate.getActionType(),
                candidate.getTitle(),
                candidate.getDeadlineText(),
                candidate.getDeadlineDate(),
                candidate.getEvidence(),
                candidate.getStatus()
        );
    }

    private String safeSourceUrl(String value) {
        if (isBlank(value)) {
            return null;
        }
        String normalized = value.strip();
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null) {
                return normalized;
            }
        } catch (IllegalArgumentException ignored) {
            // 历史脏数据不应通过来源 API 暴露为可点击链接。
        }
        return null;
    }

    private String safeManagedFileUrl(String value) {
        if (isBlank(value) || !value.startsWith(FILE_URL_PREFIX)) {
            return null;
        }
        String storedName = value.substring(FILE_URL_PREFIX.length());
        if (storedName.isBlank() || storedName.contains("/") || storedName.contains("\\")) {
            return null;
        }
        return value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
