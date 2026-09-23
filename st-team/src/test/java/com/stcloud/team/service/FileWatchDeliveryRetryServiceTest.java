package com.stcloud.team.service;

import com.stcloud.common.context.TenantContext;
import com.stcloud.team.entity.FileWatchDelivery;
import com.stcloud.team.mapper.FileWatchDeliveryMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 投递失败退避与错误脱敏测试。 */
@ExtendWith(MockitoExtension.class)
class FileWatchDeliveryRetryServiceTest {

    @Mock
    private FileWatchDeliveryMapper deliveryMapper;

    private FileWatchDeliveryRetryService service;

    @BeforeEach
    void setUp() {
        service = new FileWatchDeliveryRetryService(deliveryMapper);
        TenantContext.setTenantId(1L);
        TenantContext.setTenantMode("SAAS");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void retryStateUsesBoundedBackoffAndDoesNotPersistThrowableMessage() {
        FileWatchDelivery delivery = new FileWatchDelivery();
        delivery.setId(10L);
        delivery.setTenantId(1L);
        delivery.setStatus(FileWatchDeliveryProcessor.STATUS_PROCESSING);
        delivery.setRetryCount(0);
        when(deliveryMapper.selectByTenantIdForUpdate(1L, 10L)).thenReturn(delivery);
        when(deliveryMapper.recordFailure(eq(1L), eq(10L), eq(1), eq(FileWatchDeliveryRetryService.STATUS_RETRY),
                any(LocalDateTime.class), any(String.class))).thenReturn(1);

        service.recordFailure(1L, 10L,
                new IllegalStateException("SQL secret/path/file-name?token=credential"));

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(deliveryMapper).recordFailure(eq(1L), eq(10L), eq(1),
                eq(FileWatchDeliveryRetryService.STATUS_RETRY), any(LocalDateTime.class), errorCaptor.capture());
        assertFalse(errorCaptor.getValue().contains("secret"));
        assertFalse(errorCaptor.getValue().contains("credential"));
        assertEquals("FAILURE_IllegalStateException", errorCaptor.getValue());
    }

    @Test
    void tenthFailureIsMarkedForInvestigationAndBackoffIsCapped() {
        FileWatchDelivery delivery = new FileWatchDelivery();
        delivery.setId(11L);
        delivery.setTenantId(1L);
        delivery.setStatus(FileWatchDeliveryProcessor.STATUS_PROCESSING);
        delivery.setRetryCount(9);
        when(deliveryMapper.selectByTenantIdForUpdate(1L, 11L)).thenReturn(delivery);
        when(deliveryMapper.recordFailure(eq(1L), eq(11L), eq(10),
                eq(FileWatchDeliveryRetryService.STATUS_FAILED), any(LocalDateTime.class), any(String.class)))
                .thenReturn(1);

        service.recordFailure(1L, 11L, new RuntimeException("broker"));

        verify(deliveryMapper).recordFailure(eq(1L), eq(11L), eq(10),
                eq(FileWatchDeliveryRetryService.STATUS_FAILED), any(LocalDateTime.class), any(String.class));
        assertTrue(FileWatchDeliveryRetryService.backoffSeconds(10) <= 300L);
        assertEquals(300L, FileWatchDeliveryRetryService.backoffSeconds(10));
    }
}
