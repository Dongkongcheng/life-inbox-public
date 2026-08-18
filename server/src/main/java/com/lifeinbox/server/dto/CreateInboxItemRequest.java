package com.lifeinbox.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreateInboxItemRequest {

    @NotBlank(message = "type 不能为空")
    private String type;

    @Size(max = 255, message = "title 长度不能超过 255")
    private String title;

    private String content;

    @Size(max = 1000, message = "sourceUrl 长度不能超过 1000")
    private String sourceUrl;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
}
