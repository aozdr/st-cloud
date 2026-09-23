package com.stcloud.team.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stcloud.team.AbstractTeamIntegrationTest;
import com.stcloud.team.entity.Notification;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证通知 read 保留字在真实 Mapper SQL 中被转义。 */
class NotificationSqlIntegrationTest extends AbstractTeamIntegrationTest {

    @Test
    void unreadCountAndMarkReadUseQuotedColumn() {
        setUpUser(100L, 1L);
        Notification notification = new Notification();
        notification.setTenantId(1L);
        notification.setUserId(100L);
        notification.setType("FILE_CHANGE");
        notification.setTitle("文件变更");
        notification.setRead(0);
        notification.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(notification);

        LambdaQueryWrapper<Notification> unread = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, 1L)
                .eq(Notification::getUserId, 100L)
                .eq(Notification::getRead, 0);
        assertEquals(1L, notificationMapper.selectCount(unread));

        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, notification.getId())
                .set(Notification::getRead, 1));
        assertEquals(0L, notificationMapper.selectCount(unread));
    }
}
