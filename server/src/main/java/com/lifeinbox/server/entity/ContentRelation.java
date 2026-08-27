package com.lifeinbox.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** Java/MySQL 管理的 InboxItem 关系；第一版不保存候选、分数或解释元数据。 */
@TableName("content_relation")
public class ContentRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long leftInboxItemId;

    private Long rightInboxItemId;

    private RelationType relationType;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getLeftInboxItemId() {
        return leftInboxItemId;
    }

    public void setLeftInboxItemId(Long leftInboxItemId) {
        this.leftInboxItemId = leftInboxItemId;
    }

    public Long getRightInboxItemId() {
        return rightInboxItemId;
    }

    public void setRightInboxItemId(Long rightInboxItemId) {
        this.rightInboxItemId = rightInboxItemId;
    }

    public RelationType getRelationType() {
        return relationType;
    }

    public void setRelationType(RelationType relationType) {
        this.relationType = relationType;
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
