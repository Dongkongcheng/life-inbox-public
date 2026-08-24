package com.lifeinbox.server.controller;

import com.lifeinbox.server.dto.ActionCandidateResponse;
import com.lifeinbox.server.service.ActionCandidateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Action Candidate 产品 API；Candidate 仍需未来的用户确认，当前不会创建 Todo。 */
@RestController
@RequestMapping("/api/inbox/{inboxItemId}/action-candidates")
public class ActionCandidateController {

    private final ActionCandidateService actionCandidateService;

    public ActionCandidateController(ActionCandidateService actionCandidateService) {
        this.actionCandidateService = actionCandidateService;
    }

    @PostMapping("/extract")
    public List<ActionCandidateResponse> extract(@PathVariable Long inboxItemId) {
        return actionCandidateService.extract(inboxItemId);
    }

    @GetMapping
    public List<ActionCandidateResponse> list(@PathVariable Long inboxItemId) {
        return actionCandidateService.list(inboxItemId);
    }
}
