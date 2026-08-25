package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.ActionCandidateResponse;
import com.lifeinbox.server.entity.ActionCandidate;
import com.lifeinbox.server.entity.ActionCandidateStatus;
import com.lifeinbox.server.mapper.ActionCandidateMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 只负责把一次成功且已校验的 Action 结果原子替换进 MySQL。 */
@Service
public class ActionCandidatePersistenceService {

    private final InboxItemMapper inboxItemMapper;
    private final ActionCandidateMapper actionCandidateMapper;

    public ActionCandidatePersistenceService(
            InboxItemMapper inboxItemMapper,
            ActionCandidateMapper actionCandidateMapper
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.actionCandidateMapper = actionCandidateMapper;
    }

    /**
     * 只有 AI 成功且 Java 校验完成后才进入这里。删除与全部插入共用一个短事务，
     * 任一插入失败都会回滚；只删除 PENDING 以保护未来的 ACCEPTED/DISMISSED 决策。
     */
    @Transactional
    public List<ActionCandidateResponse> replacePending(
            Long inboxItemId,
            List<ValidatedActionCandidate> candidates
    ) {
        if (inboxItemMapper.selectActiveIdForUpdate(inboxItemId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }

        actionCandidateMapper.deletePendingByInboxItemId(inboxItemId);
        for (ValidatedActionCandidate candidate : candidates) {
            ActionCandidate entity = new ActionCandidate();
            entity.setInboxItemId(inboxItemId);
            entity.setActionType(candidate.actionType());
            entity.setTitle(candidate.title());
            entity.setDeadlineText(candidate.deadlineText());
            entity.setDeadlineDate(candidate.deadline());
            entity.setEvidence(candidate.evidence());
            entity.setStatus(ActionCandidateStatus.PENDING);
            if (actionCandidateMapper.insert(entity) != 1) {
                throw new IllegalStateException("Action Candidate 保存失败");
            }
        }

        return mapCandidates(actionCandidateMapper.selectByInboxItemId(inboxItemId));
    }

    static List<ActionCandidateResponse> mapCandidates(List<ActionCandidate> candidates) {
        if (candidates == null) {
            throw new IllegalStateException("Action Candidate 查询失败");
        }
        return candidates.stream().map(ActionCandidatePersistenceService::toResponse).toList();
    }

    static ActionCandidateResponse toResponse(ActionCandidate candidate) {
        return new ActionCandidateResponse(
                candidate.getId(),
                candidate.getInboxItemId(),
                candidate.getActionType(),
                candidate.getTitle(),
                candidate.getDeadlineText(),
                candidate.getDeadlineDate(),
                candidate.getEvidence(),
                candidate.getStatus(),
                candidate.getCreatedTime(),
                candidate.getUpdatedTime()
        );
    }
}
