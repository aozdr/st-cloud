package com.stcloud.team.service;

import com.stcloud.common.event.NotificationUnreadCountReader;
import com.stcloud.team.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 从持久通知表读取权威未读数，查询显式限制租户和接收者。 */
@Service
@RequiredArgsConstructor
public class NotificationUnreadCountService implements NotificationUnreadCountReader {

    private final NotificationMapper notificationMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long countUnread(Long tenantId, Long userId) {
        if (tenantId == null || tenantId <= 0 || userId == null || userId <= 0) {
            throw new IllegalArgumentException("通知未读数查询缺少租户或用户");
        }
        return notificationMapper.countUnread(tenantId, userId);
    }
}
