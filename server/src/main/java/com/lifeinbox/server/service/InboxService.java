package com.lifeinbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeinbox.server.dto.CreateInboxItemRequest;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

@Service
public class InboxService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final String TYPE_TEXT = "TEXT";
    private static final String TYPE_URL = "URL";

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

        if (TYPE_TEXT.equals(request.getType())) {
            if (isBlank(request.getContent())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TEXT 类型的 content 不能为空");
            }
            inboxItem.setContent(request.getContent());
        } else if (TYPE_URL.equals(request.getType())) {
            String sourceUrl = validateAndNormalizeUrl(request.getSourceUrl());
            inboxItem.setSourceUrl(sourceUrl);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "暂不支持该 InboxItem 类型");
        }

        inboxItemMapper.insert(inboxItem);
        return inboxItemMapper.selectById(inboxItem.getId());
    }

    private String validateAndNormalizeUrl(String sourceUrl) {
        if (isBlank(sourceUrl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL 类型的 sourceUrl 不能为空");
        }

        String normalizedUrl = sourceUrl.trim();
        try {
            URI uri = new URI(normalizedUrl);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceUrl 必须是合法的 HTTP 或 HTTPS URL");
            }
            return normalizedUrl;
        } catch (URISyntaxException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "sourceUrl 必须是合法的 HTTP 或 HTTPS URL"
            );
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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
