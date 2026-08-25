package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.ActionCandidateResponse;
import com.lifeinbox.server.entity.ActionCandidate;
import com.lifeinbox.server.entity.ActionCandidateStatus;
import com.lifeinbox.server.entity.ActionCandidateType;
import com.lifeinbox.server.entity.ActionProcessingStatus;
import com.lifeinbox.server.mapper.ActionCandidateMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 只负责把一次成功且已校验的 Action 结果原子替换进 MySQL。 */
@Service
public class ActionCandidatePersistenceService {

    private static final Pattern WHITESPACE = Pattern.compile(
            "\\s+",
            Pattern.UNICODE_CHARACTER_CLASS
    );

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
    public List<ActionCandidateResponse> completeSuccess(
            Long inboxItemId,
            String attemptId,
            List<ValidatedActionCandidate> candidates
    ) {
        if (inboxItemMapper.selectCurrentActionAttemptForUpdate(
                inboxItemId,
                attemptId,
                ActionProcessingStatus.PROCESSING
        ) == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Action 提取尝试已失效");
        }

        List<ActionCandidate> existing = actionCandidateMapper.selectByInboxItemIdForUpdate(
                inboxItemId
        );
        if (existing == null) {
            throw new IllegalStateException("Action Candidate 查询失败");
        }
        Set<CandidateKey> terminalKeys = terminalKeys(existing);

        actionCandidateMapper.deletePendingByInboxItemId(inboxItemId);
        for (ValidatedActionCandidate candidate : candidates) {
            // 用户已接受或忽略的完全相同建议不应再次变回待处理；这里只做确定性精确去重。
            if (terminalKeys.contains(key(candidate))) {
                continue;
            }
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

        int statusRows = inboxItemMapper.markActionSuccess(
                inboxItemId,
                attemptId,
                ActionProcessingStatus.PROCESSING,
                ActionProcessingStatus.SUCCESS
        );
        if (statusRows != 1) {
            throw new IllegalStateException("Action 成功状态保存失败");
        }

        return mapCandidates(actionCandidateMapper.selectByInboxItemId(inboxItemId));
    }

    private Set<CandidateKey> terminalKeys(List<ActionCandidate> existing) {
        Set<CandidateKey> keys = new HashSet<>();
        for (ActionCandidate candidate : existing) {
            if (candidate.getStatus() == ActionCandidateStatus.ACCEPTED
                    || candidate.getStatus() == ActionCandidateStatus.DISMISSED) {
                keys.add(key(
                        candidate.getActionType(),
                        candidate.getTitle(),
                        candidate.getDeadlineText(),
                        candidate.getDeadlineDate()
                ));
            }
        }
        return keys;
    }

    private CandidateKey key(ValidatedActionCandidate candidate) {
        return key(
                candidate.actionType(),
                candidate.title(),
                candidate.deadlineText(),
                candidate.deadline()
        );
    }

    private CandidateKey key(
            ActionCandidateType type,
            String title,
            String deadlineText,
            LocalDate deadline
    ) {
        return new CandidateKey(
                type,
                normalizeKeyText(title),
                normalizeKeyText(deadlineText),
                deadline
        );
    }

    private String normalizeKeyText(String value) {
        if (value == null) {
            return null;
        }
        return WHITESPACE.matcher(
                Normalizer.normalize(value, Normalizer.Form.NFKC)
        ).replaceAll(" ").strip();
    }

    private record CandidateKey(
            ActionCandidateType actionType,
            String title,
            String deadlineText,
            LocalDate deadline
    ) {
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
