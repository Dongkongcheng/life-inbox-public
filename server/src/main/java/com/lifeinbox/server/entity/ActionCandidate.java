package com.lifeinbox.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** AI 从某条 InboxItem 提取的持久化建议；它尚未成为用户确认的 Todo。 */
@TableName("action_candidate")
public class ActionCandidate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long inboxItemId;

    private ActionCandidateType actionType;

    private String title;

    private String deadlineText;

    private LocalDate deadlineDate;

    private String evidence;

    private ActionCandidateStatus status;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getInboxItemId() {
        return inboxItemId;
    }

    public void setInboxItemId(Long inboxItemId) {
        this.inboxItemId = inboxItemId;
    }

    public ActionCandidateType getActionType() {
        return actionType;
    }

    public void setActionType(ActionCandidateType actionType) {
        this.actionType = actionType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDeadlineText() {
        return deadlineText;
    }

    public void setDeadlineText(String deadlineText) {
        this.deadlineText = deadlineText;
    }

    public LocalDate getDeadlineDate() {
        return deadlineDate;
    }

    public void setDeadlineDate(LocalDate deadlineDate) {
        this.deadlineDate = deadlineDate;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public ActionCandidateStatus getStatus() {
        return status;
    }

    public void setStatus(ActionCandidateStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }

    public LocalDateTime getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(LocalDateTime updatedTime) {
        this.updatedTime = updatedTime;
    }
}
