package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.event.InboxActionContentReadyEvent;
import com.lifeinbox.server.event.InboxItemCapturedEvent;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 负责 Capture 的短数据库事务；事件在事务内发布，由 AFTER_COMMIT 监听器保证提交后才处理。
 */
@Service
public class InboxCapturePersistenceService {

    private final InboxItemMapper inboxItemMapper;
    private final ApplicationEventPublisher eventPublisher;

    public InboxCapturePersistenceService(
            InboxItemMapper inboxItemMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 保存统一 InboxItem，并在同一事务中登记 Capture 事件；回滚时监听器不会执行。
     */
    @Transactional
    public InboxItem save(InboxItem inboxItem) {
        int insertedRows = inboxItemMapper.insert(inboxItem);
        if (insertedRows != 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Capture 保存失败");
        }

        InboxItem savedItem = inboxItemMapper.selectById(inboxItem.getId());
        if (savedItem == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Capture 保存失败");
        }

        eventPublisher.publishEvent(new InboxItemCapturedEvent(savedItem.getId()));
        if ("TEXT".equals(savedItem.getType())) {
            // TEXT 原文提交后即是可用正文；其他类型必须等待提取正文成功写入。
            eventPublisher.publishEvent(new InboxActionContentReadyEvent(savedItem.getId()));
        }
        return savedItem;
    }
}
