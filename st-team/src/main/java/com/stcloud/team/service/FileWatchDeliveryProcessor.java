package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.event.NotificationUnreadChangedEvent;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.team.entity.FileWatch;
import com.stcloud.team.entity.FileWatchDelivery;
import com.stcloud.team.entity.Notification;
import com.stcloud.team.mapper.FileWatchDeliveryMapper;
import com.stcloud.team.mapper.FileWatchMapper;
import com.stcloud.team.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 关注投递的单条短事务处理器。
 *
 * <p>调度器只负责取 ID；本类在一个独立事务中完成抢占、即时核权、通知幂等写入和完成标记。
 * 数据库异常全部向外抛出，由 {@link FileWatchDeliveryRetryService} 在另一个短事务中安排退避重试，
 * 不能把 SQL 故障误判成失权而抑制。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileWatchDeliveryProcessor {

    static final int STATUS_PENDING = 0;
    static final int STATUS_PROCESSING = 1;
    static final int STATUS_RETRY = 2;
    static final int STATUS_SENT = 3;
    static final int STATUS_SUPPRESSED = 4;

    private static final String FILE_CHANGE = "FILE_CHANGE";
    private static final String REF_TYPE_FILE = "file";

    private final FileWatchDeliveryMapper deliveryMapper;
    private final FileWatchMapper watchMapper;
    private final NotificationMapper notificationMapper;
    private final FileWatchAccessService accessService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 处理一条投递。事务包含行锁与完成标记，进程在中途退出时会回滚到 pending/retry，重启后可恢复。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processOne(Long tenantId, Long deliveryId) {
        if (!validId(tenantId) || !validId(deliveryId)) {
            return false;
        }
        if (!tenantId.equals(TenantContext.getTenantId())) {
            throw new IllegalStateException("文件关注投递租户上下文不一致");
        }

        FileWatchDelivery delivery = deliveryMapper.selectByTenantIdForUpdate(tenantId, deliveryId);
        if (delivery == null || delivery.getStatus() == null
                || (delivery.getStatus() != STATUS_PENDING && delivery.getStatus() != STATUS_RETRY)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (delivery.getNextRetryAt() == null || delivery.getNextRetryAt().isAfter(now)) {
            return false;
        }
        if (deliveryMapper.markProcessing(tenantId, deliveryId) != 1) {
            return false;
        }

        validateDelivery(delivery, tenantId, deliveryId);
        // 投递记录中的 userId 只是捕获时的候选主体；发送前必须重新确认同租户用户仍正常且未逻辑删除。
        // 用户失效属于可预期抑制，数据库异常则由 accessService 向上抛出并进入重试。
        if (!accessService.isActiveUser(tenantId, delivery.getUserId())) {
            suppress(delivery);
            return true;
        }
        List<Long> watchIds = parseWatchIds(delivery.getWatchIds());
        if (watchIds.isEmpty()) {
            suppress(delivery);
            return true;
        }

        List<FileWatch> watches = watchMapper.findForDelivery(tenantId, delivery.getUserId(), watchIds);
        if (watches == null || watches.isEmpty()) {
            // 取消关注或重订阅后，旧代际队列是可预期的抑制，不应无限重试。
            suppress(delivery);
            return true;
        }

        boolean deletion = SyncChangeEvent.ChangeType.DELETE.name().equals(delivery.getChangeType());
        FileNode eventNode = deletion ? null : accessService.findNode(tenantId, delivery.getNodeId());
        boolean currentNodeVisible = !deletion && eventNode != null
                && accessService.canViewCurrent(tenantId, delivery.getUserId(), eventNode);

        boolean deliverable = false;
        boolean detailVisible = false;
        for (FileWatch watch : watches) {
            if (watch == null || !validId(watch.getId()) || !validId(watch.getNodeId())
                    || !tenantId.equals(watch.getTenantId())
                    || !delivery.getUserId().equals(watch.getUserId())) {
                continue;
            }
            // 捕获阶段已过滤主体，这里再核一次，避免历史/手工队列把本人操作投递给本人。
            if (delivery.getActorId() != null && delivery.getActorId().equals(delivery.getUserId())) {
                continue;
            }
            if (deletion) {
                if (accessService.canViewDeletionTarget(tenantId, delivery.getUserId(),
                        delivery.getNodeId(), watch.getNodeId())) {
                    deliverable = true;
                    break;
                }
            } else if (currentNodeVisible) {
                // 事件可能只因订阅了祖先/后代而匹配；订阅目标本身也必须仍可见。
                FileNode watchedNode = accessService.findNode(tenantId, watch.getNodeId());
                if (watchedNode != null
                        && accessService.canViewCurrent(tenantId, delivery.getUserId(), watchedNode)) {
                    deliverable = true;
                    detailVisible = true;
                    break;
                }
            } else if (SyncChangeEvent.ChangeType.MOVE.name().equals(delivery.getChangeType())
                    && eventNode != null && eventNode.isFolder()) {
                // 目录根因移动后的 ACL 不可见时，直接关注的仍可见后代可以收到通用提醒，
                // 但通知正文不能携带根名称、路径或目标 ID。
                FileNode watchedNode = accessService.findNode(tenantId, watch.getNodeId());
                if (watchedNode != null && accessService.canViewMovedDescendant(
                        tenantId, delivery.getUserId(), eventNode, watchedNode)) {
                    deliverable = true;
                    detailVisible = false;
                    break;
                }
            }
        }

        if (!deliverable) {
            suppress(delivery);
            return true;
        }

        Notification existing = notificationMapper.findByTenantUserEvent(
                tenantId, delivery.getUserId(), delivery.getEventId());
        if (existing == null) {
            Notification notification = new Notification();
            notification.setTenantId(tenantId);
            notification.setUserId(delivery.getUserId());
            notification.setType(FILE_CHANGE);
            notification.setTitle(FileWatchNotificationService.changeTitle(delivery.getChangeType()));
            // 删除事件只产生通用标题；不可访问时不能从历史 payload 拼接名称/路径。
            notification.setContent(deletion || !detailVisible || eventNode.getName() == null
                    ? null : "关注内容发生变更：" + eventNode.getName());
            notification.setRefType(REF_TYPE_FILE);
            notification.setRefId(delivery.getNodeId());
            notification.setEventId(delivery.getEventId());
            notification.setNodeId(delivery.getNodeId());
            notification.setSpaceId(delivery.getSpaceId());
            notification.setChangeType(delivery.getChangeType());
            notification.setRead(0);
            notification.setCreatedAt(delivery.getCreatedAt() == null ? now : delivery.getCreatedAt());
            try {
                notificationMapper.insert(notification);
                // 与通知写入同事务发布，监听端只在提交后推送，避免回滚产生虚假未读提醒。
                eventPublisher.publishEvent(new NotificationUnreadChangedEvent(tenantId, delivery.getUserId()));
            } catch (DuplicateKeyException duplicate) {
                // 另一实例可能已提交同一 event/user；唯一键是最终幂等守卫。
                if (notificationMapper.findByTenantUserEvent(tenantId, delivery.getUserId(),
                        delivery.getEventId()) == null) {
                    throw duplicate;
                }
            }
        }

        if (deliveryMapper.markSent(tenantId, deliveryId) != 1) {
            throw new IllegalStateException("文件关注投递完成标记失败");
        }
        return true;
    }

    private void suppress(FileWatchDelivery delivery) {
        if (deliveryMapper.markSuppressed(delivery.getTenantId(), delivery.getId()) != 1) {
            throw new IllegalStateException("文件关注投递抑制标记失败");
        }
    }

    private void validateDelivery(FileWatchDelivery delivery, Long tenantId, Long deliveryId) {
        if (!tenantId.equals(delivery.getTenantId()) || !validId(delivery.getId())
                || !deliveryId.equals(delivery.getId()) || !validId(delivery.getEventId())
                || !validId(delivery.getUserId()) || !validId(delivery.getNodeId())
                || delivery.getChangeType() == null || delivery.getChangeType().isBlank()) {
            throw new IllegalStateException("文件关注投递记录字段不完整");
        }
    }

    private List<Long> parseWatchIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()) {
                throw new IllegalStateException("文件关注投递订阅列表格式错误");
            }
            Set<Long> ids = new LinkedHashSet<>();
            for (JsonNode value : root) {
                // 全局 Jackson 会把雪花 Long ID 写成 JSON 字符串；兼容已入队的字符串和旧数字格式。
                Long id = parseWatchId(value);
                if (!validId(id)) {
                    throw new IllegalStateException("文件关注投递订阅 ID 不合法");
                }
                ids.add(id);
            }
            return new ArrayList<>(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("文件关注投递订阅列表无法解析", e);
        }
    }

    private Long parseWatchId(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            String text = value.textValue();
            if (text != null && text.matches("[1-9][0-9]*")) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private boolean validId(Long id) {
        return id != null && id > 0;
    }
}
