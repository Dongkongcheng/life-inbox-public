package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.CreateInboxItemRequest;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.service.InboxAnalyzeService;
import com.lifeinbox.server.service.InboxService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/inbox")
public class InboxController {

    private final InboxService inboxService;
    private final InboxAnalyzeService inboxAnalyzeService;

    public InboxController(InboxService inboxService, InboxAnalyzeService inboxAnalyzeService) {
        this.inboxService = inboxService;
        this.inboxAnalyzeService = inboxAnalyzeService;
    }

    /** 主 Inbox 只展示仍处于 ACTIVE 状态的条目。 */
    @GetMapping
    public List<InboxItem> list() {
        return inboxService.list();
    }

    /** TEXT 和 URL 都使用 JSON Capture，并由 Service 根据 type 做条件校验。 */
    @PostMapping
    public InboxItem create(@Valid @RequestBody CreateInboxItemRequest request) {
        return inboxService.create(request);
    }

    /** 二进制文件必须使用 multipart，避免把文件内容塞进统一 JSON DTO。 */
    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InboxItem createFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title
    ) {
        return inboxService.createFile(file, title);
    }

    /** IMAGE 复用文件存储能力，但使用更严格的图片格式和大小规则。 */
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InboxItem createImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title
    ) {
        return inboxService.createImage(file, title);
    }

    /** 一次显式分析生成完整结果；不会为 Keyword 或 Entity 再调用额外 AI 接口。 */
    @PostMapping("/{id}/ai/analyze")
    public InboxItem analyze(@PathVariable Long id) {
        return inboxAnalyzeService.analyze(id);
    }

    /** 兼容 Task 2 的旧入口，底层仍复用同一次统一 Analyze，不维护第二套 AI 流程。 */
    @PostMapping("/{id}/ai/summary")
    public InboxItem generateSummary(@PathVariable Long id) {
        return inboxAnalyzeService.analyze(id);
    }

    // 删除、归档和收藏等简单操作只负责参数转发，业务判断留在 Service。
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        inboxService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        inboxService.archive(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/favorite")
    public ResponseEntity<Void> favorite(@PathVariable Long id) {
        inboxService.favorite(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/unfavorite")
    public ResponseEntity<Void> unfavorite(@PathVariable Long id) {
        inboxService.unfavorite(id);
        return ResponseEntity.noContent().build();
    }
}
