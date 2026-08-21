package com.lifeinbox.server.service;

import com.lifeinbox.server.mapper.InboxEntityMapper;
import com.lifeinbox.server.entity.AiProcessingStatus;
import com.lifeinbox.server.mapper.InboxItemMapper;
import com.lifeinbox.server.mapper.InboxKeywordMapper;
import com.lifeinbox.server.mapper.InboxTagMapper;
import com.lifeinbox.server.mapper.TagMapper;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 无需数据库，通过真实 Spring 代理验证持久化异常会触发事务回滚。 */
@SpringJUnitConfig(InboxAnalysisTransactionProxyTests.TestConfiguration.class)
class InboxAnalysisTransactionProxyTests {

    @Autowired
    private InboxAnalysisPersistenceService persistenceService;

    @Autowired
    private InboxItemMapper inboxItemMapper;

    @Autowired
    private TagMapper tagMapper;

    @Autowired
    private InboxTagMapper inboxTagMapper;

    @Autowired
    private InboxKeywordMapper inboxKeywordMapper;

    @Autowired
    private InboxEntityMapper inboxEntityMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetMocks() {
        reset(
                inboxItemMapper,
                tagMapper,
                inboxTagMapper,
                inboxKeywordMapper,
                inboxEntityMapper,
                transactionManager
        );
    }

    @Test
    void tagFailureCausesTransactionalProxyRollbackInsteadOfCommit() {
        SimpleTransactionStatus transactionStatus = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        when(inboxItemMapper.updateAnalysis(
                1L, "attempt-1", AiProcessingStatus.PROCESSING, "新摘要", "工作"
        )).thenReturn(1);
        when(tagMapper.upsertTag("Java", "java"))
                .thenThrow(new IllegalStateException("mock tag failure"));

        assertThrows(
                IllegalStateException.class,
                () -> persistenceService.replaceAnalysis(
                        1L,
                        "attempt-1",
                        "新摘要",
                        "工作",
                        List.of(new NormalizedTag("Java", "java")),
                        List.of("并发"),
                        List.of(new NormalizedEntity("Java", "TECHNOLOGY"))
                )
        );

        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(transactionStatus);
    }

    @Test
    void lateEntityFailureRollsBackEarlierSummaryTagsAndKeywords() {
        SimpleTransactionStatus transactionStatus = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        when(inboxItemMapper.updateAnalysis(
                1L, "attempt-1", AiProcessingStatus.PROCESSING, "新摘要", "技术学习"
        )).thenReturn(1);
        when(tagMapper.selectIdByNormalizedName("java")).thenReturn(10L);
        when(inboxTagMapper.insertRelation(1L, 10L)).thenReturn(1);
        when(inboxKeywordMapper.insertKeyword(1L, "ChatModel")).thenReturn(1);
        when(inboxEntityMapper.insertEntity(1L, "Spring AI", "TECHNOLOGY"))
                .thenThrow(new IllegalStateException("mock entity failure"));

        assertThrows(
                IllegalStateException.class,
                () -> persistenceService.replaceAnalysis(
                        1L,
                        "attempt-1",
                        "新摘要",
                        "技术学习",
                        List.of(new NormalizedTag("Java", "java")),
                        List.of("ChatModel"),
                        List.of(new NormalizedEntity("Spring AI", "TECHNOLOGY"))
                )
        );

        verify(inboxTagMapper).insertRelation(1L, 10L);
        verify(inboxKeywordMapper).insertKeyword(1L, "ChatModel");
        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(transactionStatus);
    }

    @Test
    void successStatusFailureRollsBackAllSavedAnalysisResults() {
        SimpleTransactionStatus transactionStatus = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        when(inboxItemMapper.updateAnalysis(
                1L, "attempt-1", AiProcessingStatus.PROCESSING, "新摘要", "技术学习"
        )).thenReturn(1);
        when(tagMapper.selectIdByNormalizedName("java")).thenReturn(10L);
        when(inboxTagMapper.insertRelation(1L, 10L)).thenReturn(1);
        when(inboxItemMapper.markAnalysisSuccess(
                1L,
                "attempt-1",
                AiProcessingStatus.PROCESSING,
                AiProcessingStatus.SUCCESS
        )).thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> persistenceService.replaceAnalysis(
                        1L,
                        "attempt-1",
                        "新摘要",
                        "技术学习",
                        List.of(new NormalizedTag("Java", "java")),
                        List.of("ChatModel"),
                        List.of()
                )
        );

        verify(inboxKeywordMapper).insertKeyword(1L, "ChatModel");
        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(transactionStatus);
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
        TagMapper tagMapper() {
            return mock(TagMapper.class);
        }

        @Bean
        InboxTagMapper inboxTagMapper() {
            return mock(InboxTagMapper.class);
        }

        @Bean
        InboxKeywordMapper inboxKeywordMapper() {
            return mock(InboxKeywordMapper.class);
        }

        @Bean
        InboxEntityMapper inboxEntityMapper() {
            return mock(InboxEntityMapper.class);
        }

        @Bean
        InboxAnalysisPersistenceService persistenceService(
                InboxItemMapper inboxItemMapper,
                TagMapper tagMapper,
                InboxTagMapper inboxTagMapper,
                InboxKeywordMapper inboxKeywordMapper,
                InboxEntityMapper inboxEntityMapper
        ) {
            return new InboxAnalysisPersistenceService(
                    inboxItemMapper,
                    tagMapper,
                    inboxTagMapper,
                    inboxKeywordMapper,
                    inboxEntityMapper
            );
        }
    }
}
