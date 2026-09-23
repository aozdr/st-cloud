package com.stcloud.team.service;

import com.stcloud.team.AbstractTeamIntegrationTest;
import com.stcloud.team.task.FileWatchDeliveryTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** 上传事务提交前不能消费队列，提交后应立即异步唤醒。 */
@EnableAsync
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(FileWatchDeliveryTask.class)
class FileWatchDeliveryTaskEventTest extends AbstractTeamIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockBean
    private FileWatchDeliveryProcessor processor;

    @MockBean
    private FileWatchDeliveryRetryService retryService;

    @Test
    void committedDeliveryIsConsumedWithoutWaitingForScheduledScan() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(new FileWatchDeliveryReadyEvent(1L, 123L));
            verify(processor, never()).processOne(1L, 123L);
        });

        verify(processor, timeout(3000)).processOne(1L, 123L);
    }
}
