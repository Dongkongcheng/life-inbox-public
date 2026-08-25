package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.ActionCandidateAcceptanceResponse;
import com.lifeinbox.server.dto.ActionCandidateResponse;
import com.lifeinbox.server.service.ActionCandidateDecisionService;
import com.lifeinbox.server.service.ActionCandidateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Action Candidate 产品 API；只有显式 Accept 才会创建 Todo。 */
@RestController
@RequestMapping("/api/inbox/{inboxItemId}/action-candidates")
public class ActionCandidateController {

    private final ActionCandidateService actionCandidateService;
    private final ActionCandidateDecisionService decisionService;

    public ActionCandidateController(
            ActionCandidateService actionCandidateService,
            ActionCandidateDecisionService decisionService
    ) {
        this.actionCandidateService = actionCandidateService;
        this.decisionService = decisionService;
    }

    @PostMapping("/extract")
    public List<ActionCandidateResponse> extract(@PathVariable Long inboxItemId) {
        return actionCandidateService.extract(inboxItemId);
    }

    @GetMapping
    public List<ActionCandidateResponse> list(@PathVariable Long inboxItemId) {
        return actionCandidateService.list(inboxItemId);
    }

    @PostMapping("/{candidateId}/accept")
    public ActionCandidateAcceptanceResponse accept(
            @PathVariable Long inboxItemId,
            @PathVariable Long candidateId
    ) {
        return decisionService.accept(inboxItemId, candidateId);
    }

    @PostMapping("/{candidateId}/dismiss")
    public ActionCandidateResponse dismiss(
            @PathVariable Long inboxItemId,
            @PathVariable Long candidateId
    ) {
        return decisionService.dismiss(inboxItemId, candidateId);
    }
}
