package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.core.event.EventMessage;
import com.stcloud.core.event.FileWatchCaptureEvent;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.team.entity.FileWatchDelivery;
import com.stcloud.team.entity.FileWatchMatch;
import com.stcloud.team.mapper.FileWatchDeliveryMapper;
import com.stcloud.team.mapper.FileWatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.event.EventListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 在文件写事务内把一次同步事件转换成按用户聚合的持久投递记录。
 *
 * <p>这里的查询只使用当前事务可见的数据库快照，不访问 S3、MQ、ES、Redis 或网络。
 * 监听器异常必须继续向外抛出，让包含文件写入的事务整体回滚，避免出现文件已提交而关注队列缺失。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileWatchCaptureListener {

    private static final int MAX_PARENT_DEPTH = 20;
    private static final int MAX_DESCENDANT_NODES = 100_000;

    private final FileWatchMapper fileWatchMapper;
    private final FileWatchDeliveryMapper fileWatchDeliveryMapper;
    private final FileNodeMapper fileNodeMapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void capture(FileWatchCaptureEvent event) {
        // 捕获必须运行在文件写事务内；无事务时若先提交队列会形成“文件已回滚、提醒已存在”的窗口。
        // 调用方应补齐已有业务事务，不能由关注链路自行开启一个与文件写入无关的事务。
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("文件关注捕获必须运行在文件写数据库事务中");
        }
        if (event == null || event.getNodeSnapshot() == null || !validId(event.getTenantId())
                || !validId(event.getEventId())) {
            throw new IllegalStateException("文件关注捕获事件缺少租户、事件或节点快照");
        }

        EventMessage.FileNodeSnapshot snapshot = event.getNodeSnapshot();
        if (!validId(snapshot.getId()) || !Objects.equals(event.getTenantId(), snapshot.getTenantId())) {
            throw new IllegalStateException("文件关注捕获事件的节点租户不一致");
        }
        // 事件快照仅用于固定事件身份；新建节点的内存实体可能未被 ORM 回填 tenantId。
        // 必须按事件租户读取同一写事务可见的行，既补全该快照，也防止跨租户节点被订阅匹配。
        FileNode eventNode = fileNodeMapper.selectByTenantAndId(event.getTenantId(), snapshot.getId());
        if (eventNode == null || !Objects.equals(event.getTenantId(), eventNode.getTenantId())) {
            throw new IllegalStateException("文件关注捕获事件的数据库节点租户不一致");
        }

        Set<Long> currentAncestors = ancestorIds(event.getTenantId(), eventNode.getSpaceId(),
                eventNode.getParentId());
        Set<Long> oldAncestors = ancestorIds(event.getTenantId(), eventNode.getSpaceId(), event.getOldParentId());
        Set<Long> matchedNodeIds = new LinkedHashSet<>();
        matchedNodeIds.add(eventNode.getId());
        matchedNodeIds.addAll(currentAncestors);
        matchedNodeIds.addAll(oldAncestors);

        // 先查根/祖先订阅；只有该租户/空间确实存在订阅时，目录 MOVE/DELETE 才遍历后代，
        // 避免每次大目录操作都无条件扫描十万节点而拖慢原文件事务。
        List<FileWatchMatch> matches = fileWatchMapper.findMatches(event.getTenantId(), eventNode.getSpaceId(),
                matchedNodeIds);
        // Mapper 可能返回 List.of()/Collections.unmodifiableList；后续目录后代匹配需要合并，
        // 必须先复制为可变集合，避免在成功文件写事务内抛 UnsupportedOperationException。
        if (matches != null) {
            matches = new ArrayList<>(matches);
        }
        if (eventNode.isFolder() && (event.getChangeType() == SyncChangeEvent.ChangeType.MOVE
                || event.getChangeType() == SyncChangeEvent.ChangeType.DELETE)
                && fileWatchMapper.hasAnyInScope(event.getTenantId(), eventNode.getSpaceId())) {
            Set<Long> descendants = descendantIds(event.getTenantId(), eventNode);
            if (!descendants.isEmpty()) {
                matchedNodeIds.addAll(descendants);
                List<FileWatchMatch> descendantMatches = fileWatchMapper.findMatches(
                        event.getTenantId(), eventNode.getSpaceId(), descendants);
                if (descendantMatches != null && !descendantMatches.isEmpty()) {
                    if (matches == null) {
                        matches = new ArrayList<>();
                    }
                    matches.addAll(descendantMatches);
                }
            }
        }
        if (matches == null || matches.isEmpty()) {
            return;
        }

        Map<Long, List<Long>> watchIdsByUser = new LinkedHashMap<>();
        for (FileWatchMatch match : matches) {
            if (match == null || !validId(match.getUserId()) || !validId(match.getId())) {
                continue;
            }
            // 已确认的操作主体不接收自己发起的提醒；匿名主体 actorId=null 不会被误判为本人。
            if (event.getActorId() != null && event.getActorId().equals(match.getUserId())) {
                continue;
            }
            watchIdsByUser.computeIfAbsent(match.getUserId(), ignored -> new ArrayList<>())
                    .add(match.getId());
        }
        if (watchIdsByUser.isEmpty()) {
            return;
        }

        String payload = serializePayload(event, eventNode, currentAncestors, oldAncestors);
        LocalDateTime occurredAt = event.getOccurredAt() == null ? LocalDateTime.now() : event.getOccurredAt();
        for (Map.Entry<Long, List<Long>> entry : watchIdsByUser.entrySet()) {
            FileWatchDelivery delivery = new FileWatchDelivery();
            delivery.setId(IdWorker.getId());
            delivery.setTenantId(event.getTenantId());
            delivery.setEventId(event.getEventId());
            delivery.setUserId(entry.getKey());
            delivery.setNodeId(eventNode.getId());
            delivery.setSpaceId(eventNode.getSpaceId());
            delivery.setActorId(event.getActorId());
            delivery.setChangeType(event.getChangeType().name());
            delivery.setWatchIds(writeIds(entry.getValue()));
            delivery.setPayload(payload);
            delivery.setStatus(0);
            delivery.setRetryCount(0);
            delivery.setNextRetryAt(occurredAt);
            delivery.setCreatedAt(occurredAt);
            delivery.setUpdatedAt(occurredAt);
            try {
                fileWatchDeliveryMapper.insert(delivery);
                // 文件事务提交后唤醒消费端；队列行已持久化，进程中断由定时扫描补偿。
                eventPublisher.publishEvent(new FileWatchDeliveryReadyEvent(event.getTenantId(), delivery.getId()));
            } catch (DuplicateKeyException duplicate) {
                // 同一 outbox event 可能因本地重放再次捕获；唯一键保证只保留一条用户投递。
                log.debug("文件关注事件重复捕获，保留既有投递: tenantId={}, eventId={}, userId={}",
                        event.getTenantId(), event.getEventId(), entry.getKey());
            }
        }
    }

    private Set<Long> ancestorIds(Long tenantId, Long spaceId, Long parentId) {
        if (!validId(parentId)) {
            return Collections.emptySet();
        }
        Set<Long> ids = new LinkedHashSet<>();
        Long currentId = parentId;
        for (int depth = 0; depth < MAX_PARENT_DEPTH && validId(currentId); depth++) {
            if (!ids.add(currentId)) {
                throw new IllegalStateException("文件关注事件祖先链存在循环");
            }
            FileNode current = fileNodeMapper.selectByTenantAndId(tenantId, currentId);
            if (current == null || !sameSpace(spaceId, current.getSpaceId())) {
                throw new IllegalStateException("文件关注事件祖先链断裂或跨空间");
            }
            currentId = current.getParentId();
        }
        if (validId(currentId)) {
            throw new IllegalStateException("文件关注事件祖先链超过安全深度");
        }
        return ids;
    }

    private Set<Long> descendantIds(Long tenantId, FileNode root) {
        Set<Long> result = new LinkedHashSet<>();
        Set<Long> folderFrontier = new LinkedHashSet<>();
        folderFrontier.add(root.getId());
        for (int depth = 0; depth <= MAX_PARENT_DEPTH && !folderFrontier.isEmpty(); depth++) {
            List<FileNode> children = fileNodeMapper.selectChildrenByTenantSpace(tenantId, root.getSpaceId(),
                    folderFrontier);
            if (children == null || children.isEmpty()) {
                return result;
            }
            Set<Long> nextFolders = new LinkedHashSet<>();
            for (FileNode child : children) {
                if (child == null || !validId(child.getId()) || !sameSpace(root.getSpaceId(), child.getSpaceId())) {
                    throw new IllegalStateException("文件关注事件后代跨租户或跨空间");
                }
                if (!result.add(child.getId())) {
                    throw new IllegalStateException("文件关注事件后代树存在循环");
                }
                if (child.isFolder()) {
                    nextFolders.add(child.getId());
                }
                if (result.size() > MAX_DESCENDANT_NODES) {
                    throw new IllegalStateException("文件关注事件后代数量超过安全上限");
                }
            }
            folderFrontier = nextFolders;
            if (depth == MAX_PARENT_DEPTH && !folderFrontier.isEmpty()) {
                throw new IllegalStateException("文件关注事件后代树超过安全深度");
            }
        }
        return result;
    }

    private String serializePayload(FileWatchCaptureEvent event, FileNode node,
                                    Collection<Long> currentAncestors, Collection<Long> oldAncestors) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.getEventId());
        payload.put("tenantId", event.getTenantId());
        payload.put("actorId", event.getActorId());
        payload.put("changeType", event.getChangeType().name());
        // 使用当前事务内按 tenant_id 查到的节点快照，避免将未回填或陈旧的内存对象写入队列。
        payload.put("node", EventMessage.FileNodeSnapshot.from(node));
        payload.put("oldParentId", event.getOldParentId());
        payload.put("currentAncestorIds", currentAncestors);
        payload.put("oldAncestorIds", oldAncestors);
        payload.put("occurredAt", event.getOccurredAt());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("文件关注投递负载序列化失败", e);
        }
    }

    private String writeIds(Collection<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids.stream().filter(Objects::nonNull).distinct().toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("文件关注订阅 ID 序列化失败", e);
        }
    }

    private boolean sameSpace(Long left, Long right) {
        long leftValue = left == null ? 0L : left;
        long rightValue = right == null ? 0L : right;
        return leftValue == rightValue;
    }

    private boolean validId(Long value) {
        return value != null && value > 0;
    }
}
