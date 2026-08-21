package com.lifeinbox.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

    /** URL/FILE/IMAGE 的可重建检索正文；TEXT 直接使用 content，避免复制业务源数据。 */
    @JsonIgnore
    private String searchableContent;

    /** AI 为 TEXT/URL/FILE/IMAGE 生成的摘要；原始内容仍然是业务事实来源。 */
    private String summary;

    /** AI 从有限集合中选择的粗粒度分类。 */
    private String category;

    /** 最近一次统一 AI Analyze 的业务状态；是否在 Capture 后自动分析由配置决定。 */
    private AiProcessingStatus aiStatus = AiProcessingStatus.NOT_PROCESSED;

    /** 每次 Analyze 都有独立 UUID，防止已经过期的请求覆盖后来接管的新请求。 */
    private String aiAttemptId;

    /** 最近一次分析失败的安全简短说明，不保存异常栈或上游原始响应。 */
    private String aiErrorMessage;

    private LocalDateTime aiStartedTime;

    private LocalDateTime aiFinishedTime;

    /** PROCESSING 是否超过配置阈值；这是 API 派生值，不新增第五种数据库状态。 */
    @TableField(exist = false)
    private boolean aiProcessingStale;

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

    public String getSearchableContent() {
        return searchableContent;
    }

    public void setSearchableContent(String searchableContent) {
        this.searchableContent = searchableContent;
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

    public AiProcessingStatus getAiStatus() {
        return aiStatus;
    }

    public void setAiStatus(AiProcessingStatus aiStatus) {
        this.aiStatus = aiStatus;
    }

    public String getAiAttemptId() {
        return aiAttemptId;
    }

    public void setAiAttemptId(String aiAttemptId) {
        this.aiAttemptId = aiAttemptId;
    }

    public String getAiErrorMessage() {
        return aiErrorMessage;
    }

    public void setAiErrorMessage(String aiErrorMessage) {
        this.aiErrorMessage = aiErrorMessage;
    }

    public LocalDateTime getAiStartedTime() {
        return aiStartedTime;
    }

    public void setAiStartedTime(LocalDateTime aiStartedTime) {
        this.aiStartedTime = aiStartedTime;
    }

    public LocalDateTime getAiFinishedTime() {
        return aiFinishedTime;
    }

    public void setAiFinishedTime(LocalDateTime aiFinishedTime) {
        this.aiFinishedTime = aiFinishedTime;
    }

    public boolean isAiProcessingStale() {
        return aiProcessingStale;
    }

    public void setAiProcessingStale(boolean aiProcessingStale) {
        this.aiProcessingStale = aiProcessingStale;
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
