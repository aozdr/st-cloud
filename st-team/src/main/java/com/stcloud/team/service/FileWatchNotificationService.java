package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.team.dto.NotificationTargetVO;
import com.stcloud.team.dto.NotificationVO;
import com.stcloud.team.entity.Notification;
import com.stcloud.team.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 新版文件变更通知的即时映射与安全目标解析。 */
@Service
@RequiredArgsConstructor
public class FileWatchNotificationService {

    private static final String FILE_CHANGE = "FILE_CHANGE";

    private final NotificationMapper notificationMapper;
    private final FileWatchAccessService accessService;

    /**
     * 将通知映射为当前主体可见的目标数据。新 FILE_CHANGE 每次读取都复核当前节点和权限，
     * 失效时清空所有可定位字段，避免把历史名称/路径当作可访问目标返回。
     */
    public NotificationVO toVO(Notification notification, Long tenantId, Long userId) {
        NotificationVO vo = new NotificationVO();
        vo.setId(notification.getId());
        vo.setType(notification.getType());
        vo.setRefType(notification.getRefType());
        vo.setRefId(notification.getRefId());
        vo.setEventId(notification.getEventId());
        vo.setNodeId(notification.getNodeId());
        vo.setSpaceId(notification.getSpaceId());
        vo.setChangeType(notification.getChangeType());
        vo.setRead(notification.getRead());
        vo.setCreatedAt(notification.getCreatedAt());

        if (!isWatchNotification(notification)) {
            vo.setTitle(notification.getTitle());
            vo.setContent(notification.getContent());
            return vo;
        }

        FileNode node = accessService.findNode(tenantId, notification.getNodeId());
        if (node == null || !accessService.canViewCurrent(tenantId, userId, node)) {
            setUnavailable(vo);
            return vo;
        }
        vo.setAvailable(true);
        vo.setNodeId(node.getId());
        vo.setParentId(node.getParentId());
        vo.setSpaceId(node.getSpaceId());
        vo.setTitle(changeTitle(notification.getChangeType()));
        vo.setContent(node.getName() == null ? null : "关注内容发生变更：" + node.getName());
        return vo;
    }

    /** 读取通知跳转目标前先按 tenant/user 校验通知归属，再做即时节点核权。 */
    public NotificationTargetVO target(Long tenantId, Long userId, Long notificationId) {
        if (!validId(tenantId) || !validId(userId) || !validId(notificationId)) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        Notification notification = notificationMapper.selectOne(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, tenantId)
                .eq(Notification::getUserId, userId)
                .eq(Notification::getId, notificationId)
                .last("LIMIT 1"));
        if (notification == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        NotificationTargetVO unavailable = new NotificationTargetVO();
        if (!isWatchNotification(notification)) {
            return unavailable;
        }
        FileNode node = accessService.findNode(tenantId, notification.getNodeId());
        if (node == null || !accessService.canViewCurrent(tenantId, userId, node)) {
            return unavailable;
        }
        NotificationTargetVO target = new NotificationTargetVO();
        target.setAvailable(true);
        target.setNodeId(node.getId());
        target.setParentId(node.getParentId());
        target.setSpaceId(node.getSpaceId());
        target.setNodeType(node.getNodeType());
        return target;
    }

    public static String changeTitle(String changeType) {
        if (changeType == null) {
            return "关注内容发生变更";
        }
        return switch (changeType) {
            case "CREATE" -> "关注内容已创建";
            case "UPDATE" -> "关注内容已更新";
            case "MOVE" -> "关注内容已移动";
            case "RENAME" -> "关注内容已重命名";
            case "DELETE" -> "关注内容已删除";
            default -> "关注内容发生变更";
        };
    }

    private void setUnavailable(NotificationVO vo) {
        vo.setTitle("关注内容已不可用");
        vo.setContent(null);
        vo.setAvailable(false);
        // refId 也是可定位目标的旧字段；失权/删除时不能把历史节点 ID 继续透出。
        vo.setRefId(null);
        vo.setNodeId(null);
        vo.setParentId(null);
        vo.setSpaceId(null);
    }

    private boolean isWatchNotification(Notification notification) {
        return notification != null && FILE_CHANGE.equals(notification.getType())
                && notification.getEventId() != null;
    }

    private boolean validId(Long value) {
        return value != null && value > 0;
    }
}
