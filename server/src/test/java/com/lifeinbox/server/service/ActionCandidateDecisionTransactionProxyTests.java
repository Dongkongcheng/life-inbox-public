package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.ActionCandidate;
import com.lifeinbox.server.entity.ActionCandidateStatus;
import com.lifeinbox.server.entity.ActionCandidateType;
import com.lifeinbox.server.entity.Todo;
import com.lifeinbox.server.mapper.ActionCandidateMapper;
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
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Todo 创建后 Candidate 更新失败时，通过真实 Spring 代理验证外层业务事务执行 rollback。 */
@SpringJUnitConfig(ActionCandidateDecisionTransactionProxyTests.TestConfiguration.class)
class ActionCandidateDecisionTransactionProxyTests {

    @Autowired
    private ActionCandidateDecisionService decisionService;

    @Autowired
    private ActionCandidateMapper actionCandidateMapper;

    @Autowired
    private TestDependencies dependencies;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetMocks() {
        reset(actionCandidateMapper, dependencies.todoService(), transactionManager);
    }

    @Test
    void candidateUpdateFailureAfterTodoCreationRollsBackOuterTransaction() {
        SimpleTransactionStatus transactionStatus = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);

        ActionCandidate pending = new ActionCandidate();
        pending.setId(1L);
        pending.setInboxItemId(100L);
        pending.setActionType(ActionCandidateType.TODO);
        pending.setTitle("整理资料");
        pending.setEvidence("整理资料");
        pending.setStatus(ActionCandidateStatus.PENDING);
        Todo created = new Todo();
        created.setId(200L);

        when(actionCandidateMapper.selectByInboxItemIdAndIdForUpdate(100L, 1L))
                .thenReturn(pending);
        when(dependencies.todoService().findBySourceActionCandidateId(1L)).thenReturn(null);
        when(dependencies.todoService().create("整理资料", null, null, 100L, 1L))
                .thenReturn(created);
        when(actionCandidateMapper.markPendingAccepted(1L)).thenReturn(0);

        assertThrows(
                ResponseStatusException.class,
                () -> decisionService.accept(100L, 1L)
        );

        verify(dependencies.todoService()).create("整理资料", null, null, 100L, 1L);
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
        ActionCandidateMapper actionCandidateMapper() {
            return mock(ActionCandidateMapper.class);
        }

        @Bean
        TestDependencies testDependencies() {
            // 放在普通 Holder 中，避免测试用 TodoService mock 被事务自动代理。
            return new TestDependencies(mock(TodoService.class));
        }

        @Bean
        ActionCandidateDecisionService decisionService(
                ActionCandidateMapper actionCandidateMapper,
                TestDependencies dependencies
        ) {
            return new ActionCandidateDecisionService(
                    actionCandidateMapper,
                    dependencies.todoService()
            );
        }
    }

    record TestDependencies(TodoService todoService) {
    }
}
