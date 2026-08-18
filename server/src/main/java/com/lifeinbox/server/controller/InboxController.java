package com.lifeinbox.server.controller;

import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/inbox")
public class InboxController {

    private final InboxItemMapper inboxItemMapper;

    public InboxController(InboxItemMapper inboxItemMapper) {
        this.inboxItemMapper = inboxItemMapper;
    }

    @GetMapping
    public List<InboxItem> list() {
        return inboxItemMapper.selectList(null);
    }
}