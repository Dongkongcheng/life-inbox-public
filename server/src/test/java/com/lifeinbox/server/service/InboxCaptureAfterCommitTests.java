package com.lifeinbox.server.service;

import com.lifeinbox.server.entity.InboxItem;
import com.lifeinbox.server.mapper.InboxItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 使用真实 Spring 事务同步验证：只有 Capture 提交后才把 Analyze 放入后台队列。 */
@SpringJUnitConfig(InboxCaptureAfterCommitTests.TestConfiguration.class)
class InboxCaptureAfterCommitTests {

    @Autowired
    private InboxCapturePersistenceService persistenceService;

    @Autowired
    private InboxItemMapper inboxItemMapper;

    @Autowired
    private RecordingTaskExecutor taskExecutor;

    @Autowired
    private InboxAutoAnalyzeWorker worker;

    @Autowired
    private InboxAnalysisStatusService statusService;

    @Autowired
    private TestTransactionManager transactionManager;

    @BeforeEach
    void resetState() {
        reset(inboxItemMapper, worker, statusService);
        taskExecutor.clear();
        taskExecutor.setReject(false);
        transactionManager.setFailCommit(false);
    }

    @Test
    void committedCaptureQueuesAnalyzeWithoutWaitingForWorker() {
        InboxItem item = savedItem(21L);
        when(inboxItemMapper.insert(item)).thenReturn(1);
        when(inboxItemMapper.selectById(21L)).thenReturn(item);

        assertEquals(item, persistenceService.save(item));

        assertEquals(1, taskExecutor.size());
        verify(worker, never()).analyze(21L);

        taskExecutor.runFirst();
        verify(worker).analyze(21L);
    }

    @Test
    void transactionCommitFailureNeverQueuesAnalyze() {
        InboxItem item = savedItem(22L);
        when(inboxItemMapper.insert(item)).thenReturn(1);
        when(inboxItemMapper.selectById(22L)).thenReturn(item);
        transactionManager.setFailCommit(true);

        assertThrows(TransactionSystemException.class, () -> persistenceService.save(item));

        assertEquals(0, taskExecutor.size());
        verify(worker, never()).analyze(22L);
    }

    @Test
    void rejectedAfterCommitTaskStillReturnsCommittedCapture() {
        InboxItem item = savedItem(23L);
        when(inboxItemMapper.insert(item)).thenReturn(1);
        when(inboxItemMapper.selectById(23L)).thenReturn(item);
        taskExecutor.setReject(true);

        assertEquals(item, persistenceService.save(item));

        verify(statusService).markAutoSchedulingFailed(
                23L,
                "自动分析队列繁忙，请手动重试"
        );
        verify(worker, never()).analyze(23L);
    }

    private InboxItem savedItem(Long id) {
        InboxItem item = new InboxItem();
        item.setId(id);
        item.setType("TEXT");
        return item;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfiguration {

        @Bean
        TestTransactionManager transactionManager() {
            return new TestTransactionManager();
        }

        @Bean
        InboxItemMapper inboxItemMapper() {
            return mock(InboxItemMapper.class);
        }

        @Bean
        InboxAutoAnalyzeWorker worker() {
            return mock(InboxAutoAnalyzeWorker.class);
        }

        @Bean
        InboxAnalysisStatusService statusService() {
            return mock(InboxAnalysisStatusService.class);
        }

        @Bean
        RecordingTaskExecutor taskExecutor() {
            return new RecordingTaskExecutor();
        }

        @Bean
        InboxAutoAnalyzeListener listener(
                RecordingTaskExecutor taskExecutor,
                InboxAutoAnalyzeWorker worker,
                InboxAnalysisStatusService statusService
        ) {
            return new InboxAutoAnalyzeListener(taskExecutor, worker, statusService, true);
        }

        @Bean
        InboxCapturePersistenceService persistenceService(
                InboxItemMapper inboxItemMapper,
                ApplicationEventPublisher eventPublisher
        ) {
            return new InboxCapturePersistenceService(inboxItemMapper, eventPublisher);
        }
    }

    static final class RecordingTaskExecutor implements TaskExecutor {

        private final List<Runnable> tasks = new ArrayList<>();
        private boolean reject;

        @Override
        public void execute(Runnable task) {
            if (reject) {
                throw new org.springframework.core.task.TaskRejectedException("queue full");
            }
            tasks.add(task);
        }

        int size() {
            return tasks.size();
        }

        void runFirst() {
            tasks.removeFirst().run();
        }

        void clear() {
            tasks.clear();
        }

        void setReject(boolean reject) {
            this.reject = reject;
        }
    }

    static final class TestTransactionManager extends AbstractPlatformTransactionManager {

        private boolean failCommit;

        void setFailCommit(boolean failCommit) {
            this.failCommit = failCommit;
        }

        @Override
        protected Object doGetTransaction() throws TransactionException {
            return new Object();
        }

        @Override
        protected void doBegin(
                Object transaction,
                TransactionDefinition definition
        ) throws TransactionException {
            // 该测试只需要 Spring 的事务同步生命周期，不需要真实数据库资源。
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
            if (failCommit) {
                throw new TransactionSystemException("mock commit failure");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
            // 回滚由父类完成同步回调，本测试无需真实资源操作。
        }
    }
}
