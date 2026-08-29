package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.RelationProcessingResponse;
import com.lifeinbox.server.service.RelationProcessingService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Relation Discovery 手动重试入口；请求同步返回本次增量持久化计数。 */
@RestController
@RequestMapping("/api/inbox")
public class RelationProcessingController {

    private final RelationProcessingService relationProcessingService;

    public RelationProcessingController(RelationProcessingService relationProcessingService) {
        this.relationProcessingService = relationProcessingService;
    }

    @PostMapping("/{inboxItemId}/relations/discover")
    public RelationProcessingResponse discover(@PathVariable Long inboxItemId) {
        return relationProcessingService.processManual(inboxItemId);
    }

    @PostMapping("/{inboxItemId}/relations/rediscover")
    public RelationProcessingResponse rediscover(@PathVariable Long inboxItemId) {
        return relationProcessingService.processRediscovery(inboxItemId);
    }
}
