package com.lifeinbox.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lifeinbox.server.dto.AiEntityResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 所有 Capture 类型共用的核心模型。
 * 不同 type 只使用与自身相关的内容字段，避免拆成多套业务表。
 */
@TableName("inbox_item")
public class InboxItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** TEXT、URL、FILE 或 IMAGE。 */
    private String type;

    private String title;

    /** TEXT 正文。 */
    private String content;

    /** AI 为 TEXT/URL/FILE 生成的摘要；原始内容仍然是业务事实来源。 */
    private String summary;

    /** AI 从有限集合中选择的粗粒度分类。 */
    private String category;

    /** 标签存放在关系表中，这个字段只用于 API 返回，不映射 inbox_item 列。 */
    @TableField(exist = false)
    private List<String> tags = List.of();

    /** 关键词属于单条分析结果，这个聚合字段只用于 API 返回。 */
    @TableField(exist = false)
    private List<String> keywords = List.of();

    /** Entity 单独存表；这里只返回前端需要的 name/type，不暴露关系表字段。 */
    @TableField(exist = false)
    private List<AiEntityResponse> entities = List.of();

    /** URL 类型的原始网页地址。 */
    private String sourceUrl;

    /** FILE/IMAGE 的受控访问地址，不保存客户端本地路径。 */
    private String fileUrl;

    /** ACTIVE 条目显示在主 Inbox，ARCHIVED 条目暂不显示。 */
    private String status;

    /** 数据库使用 0/1 表示未收藏/已收藏。 */
    private Integer favorite;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    public List<AiEntityResponse> getEntities() {
        return entities;
    }

    public void setEntities(List<AiEntityResponse> entities) {
        this.entities = entities == null ? List.of() : List.copyOf(entities);
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getFavorite() {
        return favorite;
    }

    public void setFavorite(Integer favorite) {
        this.favorite = favorite;
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
