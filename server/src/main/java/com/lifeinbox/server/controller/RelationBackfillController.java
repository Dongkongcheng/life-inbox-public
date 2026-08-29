package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.RelationBackfillResponse;
import com.lifeinbox.server.service.RelationBackfillService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 历史补处理只能由显式产品请求启动；Controller 不负责扫描、Claim 或后台编排。 */
@RestController
@RequestMapping("/api/relations")
public class RelationBackfillController {

    private final RelationBackfillService relationBackfillService;

    public RelationBackfillController(RelationBackfillService relationBackfillService) {
        this.relationBackfillService = relationBackfillService;
    }

    @PostMapping("/backfill")
    public RelationBackfillResponse backfill(
            @RequestParam(defaultValue = "10") Integer limit
    ) {
        return relationBackfillService.schedule(limit);
    }
}
