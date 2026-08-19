package com.lifeinbox.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 内容中明确出现的人、组织、技术等实体；当前不保存实体关系。 */
@TableName("inbox_entity")
public class InboxEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long inboxItemId;

    private String name;

    private String type;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }
}
