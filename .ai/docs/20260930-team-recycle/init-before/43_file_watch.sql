SET NAMES utf8mb4;

-- 文件关注订阅：取消直接删除，重新关注生成新的订阅代际。
CREATE TABLE IF NOT EXISTS file_watch (
    id          BIGINT      NOT NULL COMMENT '订阅ID',
    tenant_id   BIGINT      NOT NULL COMMENT '租户ID',
    user_id     BIGINT      NOT NULL COMMENT '订阅主体',
    node_id     BIGINT      NOT NULL COMMENT '文件节点ID',
    created_at  DATETIME(3) NOT NULL COMMENT '关注时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_watch_tenant_user_node (tenant_id, user_id, node_id),
    KEY idx_file_watch_tenant_node_user (tenant_id, node_id, user_id),
    KEY idx_file_watch_user_created (tenant_id, user_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件关注订阅';

-- 持久投递队列：按事件和用户聚合，通知插入与 status=3 在同一事务中提交。
CREATE TABLE IF NOT EXISTS file_watch_delivery (
    id            BIGINT       NOT NULL COMMENT '投递ID',
    tenant_id     BIGINT       NOT NULL COMMENT '事件租户',
    event_id      BIGINT       NOT NULL COMMENT 'Outbox事件ID',
    user_id       BIGINT       NOT NULL COMMENT '接收用户',
    node_id       BIGINT       NOT NULL COMMENT '变更根节点',
    space_id      BIGINT       DEFAULT NULL COMMENT '团队空间',
    actor_id      BIGINT       DEFAULT NULL COMMENT '已确认操作主体，匿名为空',
    change_type   VARCHAR(16)  NOT NULL COMMENT 'CREATE/UPDATE/MOVE/RENAME/DELETE',
    watch_ids     TEXT         NOT NULL COMMENT '本事件匹配的订阅ID JSON数组',
    payload       TEXT         NOT NULL COMMENT '不含正文/凭证的事件快照',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待处理 1处理中 2重试 3已发送 4抑制 5失败待排查',
    retry_count   INT          NOT NULL DEFAULT 0 COMMENT '失败重试次数',
    next_retry_at DATETIME(3)  NOT NULL COMMENT '下次处理时间',
    last_error    VARCHAR(500) DEFAULT NULL COMMENT '脱敏错误摘要',
    created_at    DATETIME(3)  NOT NULL COMMENT '入队时间',
    updated_at    DATETIME(3)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_watch_delivery_event_user (tenant_id, event_id, user_id),
    KEY idx_file_watch_delivery_due (status, next_retry_at, id),
    KEY idx_file_watch_delivery_user_node (tenant_id, user_id, node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件关注持久投递队列';

-- 新版关注通知使用可空事件字段；旧通知的 event_id 保持 NULL 以兼容原写入路径。
ALTER TABLE notification
    ADD COLUMN event_id BIGINT DEFAULT NULL COMMENT '关注变更事件ID' AFTER ref_id,
    ADD COLUMN node_id BIGINT DEFAULT NULL COMMENT '关注变更节点ID' AFTER event_id,
    ADD COLUMN space_id BIGINT DEFAULT NULL COMMENT '关注变更团队空间ID' AFTER node_id,
    ADD COLUMN change_type VARCHAR(16) DEFAULT NULL COMMENT '关注变更类型' AFTER space_id,
    ADD UNIQUE KEY uk_notification_tenant_user_event (tenant_id, user_id, event_id);
