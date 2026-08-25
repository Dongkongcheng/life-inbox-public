package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.ActionCandidateAcceptanceResponse;
import com.lifeinbox.server.dto.ActionCandidateResponse;
import com.lifeinbox.server.dto.TodoResponse;
import com.lifeinbox.server.entity.ActionCandidate;
import com.lifeinbox.server.entity.ActionCandidateStatus;
import com.lifeinbox.server.entity.Todo;
import com.lifeinbox.server.mapper.ActionCandidateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 用户对 AI Candidate 的最终决策；这里不重新调用 AI，也不允许客户端改写 Todo 内容。 */
@Service
public class ActionCandidateDecisionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActionCandidateDecisionService.class);

    private final ActionCandidateMapper actionCandidateMapper;
    private final TodoService todoService;

    public ActionCandidateDecisionService(
            ActionCandidateMapper actionCandidateMapper,
            TodoService todoService
    ) {
        this.actionCandidateMapper = actionCandidateMapper;
        this.todoService = todoService;
    }

    /** Todo 创建与 Candidate 状态更新共享一个短事务，任一步失败都会整体回滚。 */
    @Transactional
    public ActionCandidateAcceptanceResponse accept(Long inboxItemId, Long candidateId) {
        ActionCandidate candidate = requireCandidateForUpdate(inboxItemId, candidateId);
        ActionCandidateStatus status = requireKnownStatus(candidate);

        if (status == ActionCandidateStatus.ACCEPTED) {
            // 网络重试或重复点击直接返回原 Todo，不能创建第二条业务任务。
            Todo existing = todoService.findBySourceActionCandidateId(candidateId);
            if (existing == null) {
                throw integrityFailure(candidateId, "ACCEPTED Candidate 缺少关联 Todo");
            }
            return acceptanceResponse(candidate, existing);
        }
        if (status == ActionCandidateStatus.DISMISSED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "已忽略的 Action Candidate 不能接受"
            );
        }

        if (todoService.findBySourceActionCandidateId(candidateId) != null) {
            throw integrityFailure(candidateId, "PENDING Candidate 已存在关联 Todo");
        }

        // 只复制一次明确的业务字段；evidence、deadlineText 和 actionType 仍属于 Candidate。
        Todo todo = todoService.create(
                candidate.getTitle(),
                null,
                candidate.getDeadlineDate(),
                candidate.getInboxItemId(),
                candidate.getId()
        );
        if (actionCandidateMapper.markPendingAccepted(candidateId) != 1) {
            throw updateFailure(candidateId);
        }

        return acceptanceResponse(
                requireUpdatedStatus(candidateId, ActionCandidateStatus.ACCEPTED),
                todo
        );
    }

    /** Dismiss 只记录用户决定，不删除 Candidate，也不触碰任何 Todo。 */
    @Transactional
    public ActionCandidateResponse dismiss(Long inboxItemId, Long candidateId) {
        ActionCandidate candidate = requireCandidateForUpdate(inboxItemId, candidateId);
        ActionCandidateStatus status = requireKnownStatus(candidate);

        if (status == ActionCandidateStatus.DISMISSED) {
            return ActionCandidatePersistenceService.toResponse(candidate);
        }
        if (status == ActionCandidateStatus.ACCEPTED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "已接受的 Action Candidate 不能忽略"
            );
        }

        if (actionCandidateMapper.markPendingDismissed(candidateId) != 1) {
            throw updateFailure(candidateId);
        }
        return ActionCandidatePersistenceService.toResponse(
                requireUpdatedStatus(candidateId, ActionCandidateStatus.DISMISSED)
        );
    }

    private ActionCandidate requireCandidateForUpdate(Long inboxItemId, Long candidateId) {
        ActionCandidate candidate = actionCandidateMapper.selectByInboxItemIdAndIdForUpdate(
                inboxItemId,
                candidateId
        );
        if (candidate == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Action Candidate 不存在");
        }
        return candidate;
    }

    private ActionCandidateStatus requireKnownStatus(ActionCandidate candidate) {
        if (candidate.getStatus() == null) {
            throw integrityFailure(candidate.getId(), "Candidate 状态为空");
        }
        return candidate.getStatus();
    }

    private ActionCandidate requireUpdatedStatus(
            Long candidateId,
            ActionCandidateStatus expectedStatus
    ) {
        ActionCandidate updated = actionCandidateMapper.selectById(candidateId);
        if (updated == null || updated.getStatus() != expectedStatus) {
            throw integrityFailure(candidateId, "Candidate 状态更新后回读不一致");
        }
        return updated;
    }

    private ActionCandidateAcceptanceResponse acceptanceResponse(
            ActionCandidate candidate,
            Todo todo
    ) {
        return new ActionCandidateAcceptanceResponse(
                ActionCandidatePersistenceService.toResponse(candidate),
                TodoResponse.from(todo)
        );
    }

    private ResponseStatusException updateFailure(Long candidateId) {
        LOGGER.error("Action Candidate 状态更新失败，candidateId={}", candidateId);
        return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Action Candidate 状态更新失败"
        );
    }

    private ResponseStatusException integrityFailure(Long candidateId, String reason) {
        // 日志只记录内部 ID 和固定原因，不暴露 SQL、AI 原文或用户正文。
        LOGGER.error("Action Candidate 数据完整性异常，candidateId={}，reason={}", candidateId, reason);
        return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Action Candidate 数据状态不完整"
        );
    }
}
