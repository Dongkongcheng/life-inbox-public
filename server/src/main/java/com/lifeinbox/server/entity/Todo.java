package com.lifeinbox.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 用户业务任务；来源只用于追溯，不能决定或覆盖 Todo 自身状态。 */
@TableName("todo")
public class Todo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sourceInboxItemId;

    private Long sourceActionCandidateId;

    private String title;

    private String description;

    private TodoStatus status;

    private LocalDate dueDate;

    private LocalDateTime completedTime;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSourceInboxItemId() {
        return sourceInboxItemId;
    }

    public void setSourceInboxItemId(Long sourceInboxItemId) {
        this.sourceInboxItemId = sourceInboxItemId;
    }

    public Long getSourceActionCandidateId() {
        return sourceActionCandidateId;
    }

    public void setSourceActionCandidateId(Long sourceActionCandidateId) {
        this.sourceActionCandidateId = sourceActionCandidateId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public TodoStatus getStatus() {
        return status;
    }

    public void setStatus(TodoStatus status) {
        this.status = status;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public LocalDateTime getCompletedTime() {
        return completedTime;
    }

    public void setCompletedTime(LocalDateTime completedTime) {
        this.completedTime = completedTime;
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
