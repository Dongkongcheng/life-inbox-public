package com.lifeinbox.server.service;

import com.lifeinbox.server.dto.CreateInboxItemRequest;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InboxService {

    private final InboxItemMapper inboxItemMapper;

    public InboxService(InboxItemMapper inboxItemMapper) {
        this.inboxItemMapper = inboxItemMapper;
    }

    public List<InboxItem> list() {
        return inboxItemMapper.selectList(null);
    }

    public InboxItem create(CreateInboxItemRequest request) {
        InboxItem inboxItem = new InboxItem();
        inboxItem.setType(request.getType());
        inboxItem.setTitle(request.getTitle());
        inboxItem.setContent(request.getContent());

        inboxItemMapper.insert(inboxItem);
        return inboxItemMapper.selectById(inboxItem.getId());
    }
}
