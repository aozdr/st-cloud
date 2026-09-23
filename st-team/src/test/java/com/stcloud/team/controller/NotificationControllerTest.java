package com.stcloud.team.controller;

import com.stcloud.team.mapper.NotificationMapper;
import com.stcloud.team.service.FileWatchNotificationService;
import com.stcloud.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private FileWatchNotificationService fileWatchNotificationService;

    @InjectMocks
    private NotificationController controller;

    @Test
    void notificationPageRejectsUnboundedOrInvalidPageSizeBeforeQuerying() {
        assertThrows(BusinessException.class, () -> controller.list(0, 20));
        assertThrows(BusinessException.class, () -> controller.list(1, 0));
        assertThrows(BusinessException.class, () -> controller.list(1, 101));

        verifyNoInteractions(notificationMapper, fileWatchNotificationService);
    }
}
