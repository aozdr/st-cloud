package com.stcloud.core.event;

import com.stcloud.core.entity.FileNode;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 事件消息负载（TASK-004）：Outbox 落库与 RocketMQ 投递的可序列化事件载体。
 * <p>
 * 业务事务内由 {@link ReliableEventPublisher} 序列化为 JSON 写入 event_log.payload，
 * 事务提交后由 {@link com.stcloud.core.outbox.EventRelay} 投递 RocketMQ，消费端反序列化执行。
 * 不直接序列化 FileNode（避免携带 MyBatis-Plus 注解与无关字段），改用 FileNodeSnapshot 快照，
 * 消费端经 toFileNode() 还原后复用现有 SearchService / SyncChangeLog 写入逻辑。
 */
@Data
@NoArgsConstructor
public class EventMessage {

    /** 事件类型：FILE_INDEX / SYNC_CHANGE / PHYSICAL_DELETE */
    private String eventType;

    /** 文件索引动作：INDEX / DELETE / UPDATE_META（仅 FILE_INDEX 事件） */
    private String actionType;

    /** 同步变更类型：CREATE / UPDATE / MOVE / RENAME / DELETE（仅 SYNC_CHANGE 事件） */
    private String changeType;

    /** 变更前路径（仅 MOVE / RENAME 场景携带） */
    private String oldPath;

    /** 事件日志ID（Outbox 主键，消费者幂等键；本地兜底时无） */
    private Long eventLogId;

    /** 文件节点快照 */
    private FileNodeSnapshot fileNode;

    /** 由文件索引事件构建 */
    public static EventMessage fromFileIndex(FileNode node, FileIndexEvent.ActionType action, Long eventLogId) {
        EventMessage message = new EventMessage();
        message.setEventType("FILE_INDEX");
        message.setActionType(action.name());
        message.setEventLogId(eventLogId);
        message.setFileNode(FileNodeSnapshot.from(node));
        return message;
    }

    /** 由同步变更事件构建 */
    public static EventMessage fromSyncChange(FileNode node, SyncChangeEvent.ChangeType change, String oldPath, Long eventLogId) {
        EventMessage message = new EventMessage();
        message.setEventType("SYNC_CHANGE");
        message.setChangeType(change.name());
        message.setOldPath(oldPath);
        message.setEventLogId(eventLogId);
        message.setFileNode(FileNodeSnapshot.from(node));
        return message;
    }

    /** 由物理删除事件构建（事务边界治理 F4：回收站永久删除引用归零后的 S3 异步删除补偿） */
    public static EventMessage fromPhysicalDelete(FileNode node, Long eventLogId) {
        EventMessage message = new EventMessage();
        message.setEventType("PHYSICAL_DELETE");
        message.setEventLogId(eventLogId);
        // payload 快照含 storagePath / fileMd5 / tenantId / objectId，供消费端定位并删除 S3 物理对象
        message.setFileNode(FileNodeSnapshot.from(node));
        return message;
    }

    /**
     * FileNode 业务字段快照：仅保留消费端（搜索索引 / 同步日志）所需字段。
     */
    @Data
    @NoArgsConstructor
    public static class FileNodeSnapshot {

        private Long id;
        private Long tenantId;
        private Long parentId;
        private Integer nodeType;
        private String name;
        private String path;
        private Long fileSize;
        private String fileMd5;
        private String contentType;
        private String suffix;
        private String storagePath;
        private Long objectId;
        private Integer status;
        private Integer uploadStatus;
        private Long uploaderId;
        private Long ownerId;
        private Long spaceId;
        private Integer refCount;
        private Integer version;
        private String thumbnailPath;
        private Integer hidden;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        /** 由 FileNode 实体构建快照 */
        public static FileNodeSnapshot from(FileNode node) {
            FileNodeSnapshot snapshot = new FileNodeSnapshot();
            snapshot.setId(node.getId());
            snapshot.setTenantId(node.getTenantId());
            snapshot.setParentId(node.getParentId());
            snapshot.setNodeType(node.getNodeType());
            snapshot.setName(node.getName());
            snapshot.setPath(node.getPath());
            snapshot.setFileSize(node.getFileSize());
            snapshot.setFileMd5(node.getFileMd5());
            snapshot.setContentType(node.getContentType());
            snapshot.setSuffix(node.getSuffix());
            snapshot.setStoragePath(node.getStoragePath());
            snapshot.setObjectId(node.getObjectId());
            snapshot.setStatus(node.getStatus());
            snapshot.setUploadStatus(node.getUploadStatus());
            snapshot.setUploaderId(node.getUploaderId());
            snapshot.setOwnerId(node.getOwnerId());
            snapshot.setSpaceId(node.getSpaceId());
            snapshot.setRefCount(node.getRefCount());
            snapshot.setVersion(node.getVersion());
            snapshot.setThumbnailPath(node.getThumbnailPath());
            snapshot.setHidden(node.getHidden());
            snapshot.setCreatedAt(node.getCreatedAt());
            snapshot.setUpdatedAt(node.getUpdatedAt());
            return snapshot;
        }

        /** 还原为 FileNode（供消费端复用 SearchService / SyncService 签名） */
        public FileNode toFileNode() {
            FileNode node = new FileNode();
            node.setId(id);
            node.setTenantId(tenantId);
            node.setParentId(parentId);
            node.setNodeType(nodeType);
            node.setName(name);
            node.setPath(path);
            node.setFileSize(fileSize);
            node.setFileMd5(fileMd5);
            node.setContentType(contentType);
            node.setSuffix(suffix);
            node.setStoragePath(storagePath);
            node.setObjectId(objectId);
            node.setStatus(status);
            node.setUploadStatus(uploadStatus);
            node.setUploaderId(uploaderId);
            node.setOwnerId(ownerId);
            node.setSpaceId(spaceId);
            node.setRefCount(refCount);
            node.setVersion(version);
            node.setThumbnailPath(thumbnailPath);
            node.setHidden(hidden);
            node.setCreatedAt(createdAt);
            node.setUpdatedAt(updatedAt);
            return node;
        }
    }
}
