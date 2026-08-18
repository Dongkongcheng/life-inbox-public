package com.lifeinbox.server.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateInboxItemRequest {

    @NotBlank(message = "type 不能为空")
    private String type;

    private String title;

    @NotBlank(message = "content 不能为空")
    private String content;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
