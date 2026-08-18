package com.lifeinbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeinbox.server.dto.CreateInboxItemRequest;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class InboxService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    private final InboxItemMapper inboxItemMapper;

    public InboxService(InboxItemMapper inboxItemMapper) {
        this.inboxItemMapper = inboxItemMapper;
    }

    public List<InboxItem> list() {
        LambdaQueryWrapper<InboxItem> query = new LambdaQueryWrapper<>();
        query.eq(InboxItem::getStatus, STATUS_ACTIVE);
        return inboxItemMapper.selectList(query);
    }

    public InboxItem create(CreateInboxItemRequest request) {
        InboxItem inboxItem = new InboxItem();
        inboxItem.setType(request.getType());
        inboxItem.setTitle(request.getTitle());
        inboxItem.setContent(request.getContent());

        inboxItemMapper.insert(inboxItem);
        return inboxItemMapper.selectById(inboxItem.getId());
    }

    public void delete(Long id) {
        int deletedRows = inboxItemMapper.deleteById(id);
        if (deletedRows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
    }

    public void archive(Long id) {
        InboxItem inboxItem = inboxItemMapper.selectById(id);
        if (inboxItem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }

        inboxItem.setStatus(STATUS_ARCHIVED);
        int updatedRows = inboxItemMapper.updateById(inboxItem);
        if (updatedRows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
    }

    public void favorite(Long id) {
        updateFavorite(id, 1);
    }

    public void unfavorite(Long id) {
        updateFavorite(id, 0);
    }

    private void updateFavorite(Long id, int favorite) {
        InboxItem inboxItem = inboxItemMapper.selectById(id);
        if (inboxItem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }

        inboxItem.setFavorite(favorite);
        int updatedRows = inboxItemMapper.updateById(inboxItem);
        if (updatedRows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "InboxItem 不存在");
        }
    }
}
