package com.stcloud.team.util;

import com.stcloud.team.entity.Notification;
import com.stcloud.team.mapper.NotificationMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 通知创建工具
 * 同步写入（通知量小，无需异步）
 */
@Slf4j
@Component
public class NotificationHelper {

    @Resource
    private NotificationMapper notificationMapper;

    /**
     * 创建站内通知
     *
     * @param userId   接收者ID
     * @param type     通知类型：MENTION/TEAM_INVITE/FILE_CHANGE/MEMBER_CHANGE
     * @param title    标题
     * @param content  正文
     * @param refType  关联类型：team/comment/file
     * @param refId    关联ID
     */
    public void notify(Long userId, String type, String title,
                       String content, String refType, Long refId) {
        try {
            Notification n = new Notification();
            n.setUserId(userId);
            n.setType(type);
            n.setTitle(title);
            n.setContent(content);
            n.setRefType(refType);
            n.setRefId(refId);
            n.setRead(0);
            n.setCreatedAt(LocalDateTime.now());
            notificationMapper.insert(n);
        } catch (Exception e) {
            log.error("创建通知失败: userId={}, type={}", userId, type, e);
        }
    }
}