package com.lifeinbox.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 单条 InboxItem 的分析关键词；Keyword 不建立可复用字典，避免与 Tag 混淆。 */
@TableName("inbox_keyword")
public class InboxKeyword {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long inboxItemId;

    private String keyword;

    private LocalDateTime createdTime;

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

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }
}
