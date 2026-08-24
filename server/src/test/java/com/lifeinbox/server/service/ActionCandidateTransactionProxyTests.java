package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.ActionCandidate;
import com.lifeinbox.server.entity.ActionCandidateType;
import com.lifeinbox.server.mapper.ActionCandidateMapper;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 无需真实数据库，通过 Spring 代理验证 delete 后的 insert 异常触发事务回滚。 */
@SpringJUnitConfig(ActionCandidateTransactionProxyTests.TestConfiguration.class)
class ActionCandidateTransactionProxyTests {

    @Autowired
    private ActionCandidatePersistenceService persistenceService;

    @Autowired
    private InboxItemMapper inboxItemMapper;

    @Autowired
    private ActionCandidateMapper actionCandidateMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetMocks() {
        reset(inboxItemMapper, actionCandidateMapper, transactionManager);
    }

    @Test
    void insertFailureAfterDeleteCausesRollbackInsteadOfCommit() {
        SimpleTransactionStatus transactionStatus = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        when(inboxItemMapper.selectActiveIdForUpdate(1L)).thenReturn(1L);
        when(actionCandidateMapper.insert(any(ActionCandidate.class)))
                .thenThrow(new IllegalStateException("mock insert failure"));

        assertThrows(
                IllegalStateException.class,
                () -> persistenceService.replacePending(1L, List.of(
                        new ValidatedActionCandidate(
                                ActionCandidateType.TODO,
                                "整理资料",
                                null,
                                null,
                                "整理资料"
                        )
                ))
        );

        verify(actionCandidateMapper).deletePendingByInboxItemId(1L);
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
        ActionCandidateMapper actionCandidateMapper() {
            return mock(ActionCandidateMapper.class);
        }

        @Bean
        ActionCandidatePersistenceService persistenceService(
                InboxItemMapper inboxItemMapper,
                ActionCandidateMapper actionCandidateMapper
        ) {
            return new ActionCandidatePersistenceService(inboxItemMapper, actionCandidateMapper);
        }
    }
}
