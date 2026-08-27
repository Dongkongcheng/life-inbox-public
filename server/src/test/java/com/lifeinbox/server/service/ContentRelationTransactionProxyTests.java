package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.ContentRelation;
import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.entity.RelationType;
import com.lifeinbox.server.mapper.ContentRelationMapper;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 使用真实 Spring 代理确认端点锁、Relation 写入与回读位于同一个短事务。 */
@SpringJUnitConfig(ContentRelationTransactionProxyTests.TestConfiguration.class)
class ContentRelationTransactionProxyTests {

    @Autowired
    private ContentRelationService service;

    @Autowired
    private InboxItemMapper inboxItemMapper;

    @Autowired
    private ContentRelationMapper contentRelationMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetMocks() {
        reset(inboxItemMapper, contentRelationMapper, transactionManager);
    }

    @Test
    void ensureRelatedToRunsThroughTransactionProxy() {
        SimpleTransactionStatus transaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transaction);
        when(inboxItemMapper.selectRelationEndpointsForUpdate(10L, 20L))
                .thenReturn(List.of(item(10L), item(20L)));
        ContentRelation saved = relation(1L, 10L, 20L);
        when(contentRelationMapper.selectCanonicalPair(10L, 20L, RelationType.RELATED_TO))
                .thenReturn(null, saved);
        when(contentRelationMapper.insert(any(ContentRelation.class))).thenReturn(1);

        assertSame(saved, service.ensureRelatedTo(20L, 10L));

        verify(transactionManager).commit(transaction);
    }

    private InboxItem item(Long id) {
        InboxItem item = new InboxItem();
        item.setId(id);
        item.setStatus("ACTIVE");
        return item;
    }

    private ContentRelation relation(Long id, Long leftInboxItemId, Long rightInboxItemId) {
        ContentRelation relation = new ContentRelation();
        relation.setId(id);
        relation.setLeftInboxItemId(leftInboxItemId);
        relation.setRightInboxItemId(rightInboxItemId);
        relation.setRelationType(RelationType.RELATED_TO);
        return relation;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfiguration {

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        InboxItemMapper inboxItemMapper() {
            return mock(InboxItemMapper.class);
        }

        @Bean
        ContentRelationMapper contentRelationMapper() {
            return mock(ContentRelationMapper.class);
        }

        @Bean
        ContentRelationService contentRelationService(
                InboxItemMapper inboxItemMapper,
                ContentRelationMapper contentRelationMapper
        ) {
            return new ContentRelationService(inboxItemMapper, contentRelationMapper);
        }
    }
}
