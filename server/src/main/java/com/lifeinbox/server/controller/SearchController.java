package com.lifeinbox.server.controller;

import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.service.InboxService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 面向产品的搜索入口：只处理 HTTP 参数，查询校验和数据检索仍由 InboxService 负责。
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final InboxService inboxService;

    public SearchController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @GetMapping
    public List<InboxItem> search(
            @RequestParam("q") String query,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "favorite", required = false) Boolean favorite
    ) {
        return inboxService.search(query, type, category, favorite);
    }
}
