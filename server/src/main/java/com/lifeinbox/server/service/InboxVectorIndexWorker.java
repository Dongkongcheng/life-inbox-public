package com.lifeinbox.server.service;

import com.lifeinbox.server.client.AiServiceClient;
import com.lifeinbox.server.dto.AiVectorDeleteResponse;
import com.lifeinbox.server.dto.AiVectorIndexResponse;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.event.InboxVectorReadyEvent;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 在数据库事务外执行派生向量生命周期；每次索引前重新读取 MySQL 当前状态和正文。
 */
@Service
public class InboxVectorIndexWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxVectorIndexWorker.class);
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final int LOCK_STRIPES = 64;

    private final ReentrantLock[] itemLocks = createItemLocks();
    private final InboxItemMapper inboxItemMapper;
    private final InboxSearchableContentService searchableContentService;
    private final AiServiceClient aiServiceClient;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public InboxVectorIndexWorker(
            InboxItemMapper inboxItemMapper,
            InboxSearchableContentService searchableContentService,
            AiServiceClient aiServiceClient,
            ApplicationEventPublisher eventPublisher
    ) {
        this.inboxItemMapper = inboxItemMapper;
        this.searchableContentService = searchableContentService;
        this.aiServiceClient = aiServiceClient;
        this.eventPublisher = eventPublisher;
    }

    InboxVectorIndexWorker(
            InboxItemMapper inboxItemMapper,
            InboxSearchableContentService searchableContentService,
            AiServiceClient aiServiceClient
    ) {
        this(inboxItemMapper, searchableContentService, aiServiceClient, event -> { });
    }

    public void index(Long inboxItemId, String expectedAttemptId) {
        ReentrantLock itemLock = itemLock(inboxItemId);
        itemLock.lock();
        try {
            InboxItem current = inboxItemMapper.selectById(inboxItemId);
            if (current == null || !STATUS_ACTIVE.equals(current.getStatus())) {
                return;
            }
            if (!ownsCurrentContent(current, expectedAttemptId)) {
                LOGGER.info("忽略已失效 Attempt 的 Vector Index，InboxItem={}", inboxItemId);
                return;
            }

            String searchableContent = searchableContentService.resolveForRetrieval(current);
            if (searchableContent == null) {
                // URL/FILE/IMAGE 在 Analyze 准备正文前允许没有 Vector，不做查询时提取或启动回填。
                return;
            }

            AiVectorIndexResponse response = aiServiceClient.indexVector(
                    inboxItemId,
                    searchableContent
            );
            if (response.indexed()) {
                LOGGER.info(
                        "Vector Index 完成，InboxItem={}，Collection={}，Model={}，Dimension={}",
                        inboxItemId,
                        response.collection(),
                        response.model(),
                        response.dimension()
                );
                // 以真实 Vector 成功作为唯一自动发现钩子；不扫描历史数据，也不在 Capture 事务内调用 AI。
                eventPublisher.publishEvent(new InboxVectorReadyEvent(inboxItemId));
            } else {
                LOGGER.debug("Vector Store 已关闭，跳过 InboxItem={} 的索引", inboxItemId);
            }
        } catch (RuntimeException exception) {
            // Vector 是可重建派生索引；失败不能修改 AI 状态、回滚 Capture 或清空 Searchable Content。
            LOGGER.warn("InboxItem {} 的 Vector Index 失败", inboxItemId, exception);
        } finally {
            itemLock.unlock();
        }
    }

    public void delete(Long inboxItemId) {
        ReentrantLock itemLock = itemLock(inboxItemId);
        itemLock.lock();
        try {
            AiVectorDeleteResponse response = aiServiceClient.deleteVector(inboxItemId);
            if (response.deleted()) {
                LOGGER.info("Vector Point 删除完成，InboxItem={}", inboxItemId);
            } else {
                LOGGER.debug("Vector Store 已关闭，跳过 InboxItem={} 的 Point 删除", inboxItemId);
            }
        } catch (RuntimeException exception) {
            // MySQL 已经完成 Archive/Delete；Qdrant 故障只能记录，不能逆转业务操作。
            LOGGER.warn("InboxItem {} 的 Vector Point 删除失败", inboxItemId, exception);
        } finally {
            itemLock.unlock();
        }
    }

    private boolean ownsCurrentContent(InboxItem current, String expectedAttemptId) {
        if (expectedAttemptId == null) {
            // Capture 事件只处理尚未进入 Analyze 的 TEXT；Analyze 已接管时由它自己的 Attempt 触发。
            return current.getAiAttemptId() == null;
        }
        return expectedAttemptId.equals(current.getAiAttemptId());
    }

    private ReentrantLock itemLock(Long inboxItemId) {
        int index = Math.floorMod(Long.hashCode(inboxItemId), LOCK_STRIPES);
        return itemLocks[index];
    }

    private static ReentrantLock[] createItemLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            // 同一条目的旧 Index、新 Index 与 Delete 串行，最终操作再读取 MySQL 当前事实。
            locks[index] = new ReentrantLock(true);
        }
        return locks;
    }
}
