package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.RelatedInboxItemResponse;
import com.lifeinbox.server.service.RelatedInboxItemService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** InboxItem-centered Related Items 产品入口；普通 GET 只读取 MySQL，不触发关系发现。 */
@RestController
@RequestMapping("/api/inbox")
public class RelatedInboxItemController {

    private final RelatedInboxItemService relatedInboxItemService;

    public RelatedInboxItemController(RelatedInboxItemService relatedInboxItemService) {
        this.relatedInboxItemService = relatedInboxItemService;
    }

    @GetMapping("/{inboxItemId}/related")
    public List<RelatedInboxItemResponse> listRelated(
            @PathVariable Long inboxItemId,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return relatedInboxItemService.listRelated(inboxItemId, limit);
    }
}
