SET NAMES utf8mb4;
-- ============================================================
-- 同步变更日志 + 同步根增强（st-sync 模块）
-- ============================================================

-- 变更日志表：单调递增 ID 作为同步游标，取代时间戳游标
CREATE TABLE IF NOT EXISTS sync_change_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '日志ID（即同步游标）',
    tenant_id       BIGINT       NOT NULL                 COMMENT '租户ID',
    user_id         BIGINT       NOT NULL                 COMMENT '文件所有者ID',
    file_node_id    BIGINT       NOT NULL                 COMMENT '文件节点ID',
    change_type     VARCHAR(16)  NOT NULL                 COMMENT '变更类型：CREATE/UPDATE/MOVE/RENAME/DELETE',
    path            VARCHAR(1024) NOT NULL                COMMENT '变更后完整路径',
    old_path        VARCHAR(1024) DEFAULT NULL            COMMENT '变更前完整路径（MOVE/RENAME 用）',
    name            VARCHAR(255) NOT NULL                 COMMENT '节点名称',
    node_type       TINYINT      NOT NULL                 COMMENT '0-文件夹 1-文件',
    file_md5        VARCHAR(64)  DEFAULT NULL             COMMENT '文件MD5',
    file_size       BIGINT       DEFAULT 0                COMMENT '文件大小（字节）',
    event_log_id    BIGINT       DEFAULT NULL             COMMENT '事件Outbox日志ID（MQ 幂等键，本地兜底为 NULL）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '日志创建时间',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id, id),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='同步变更日志表';

-- 同步根增强：冲突策略 + 最后同步时间
ALTER TABLE sync_root
    ADD COLUMN conflict_strategy VARCHAR(16) NOT NULL DEFAULT 'keep_both'
        COMMENT '冲突策略：keep_both/latest_wins/server_wins/local_wins' AFTER status,
    ADD COLUMN last_sync_at DATETIME DEFAULT NULL
        COMMENT '最后同步时间' AFTER conflict_strategy;

-- 旧游标（timestamp ms）归零，触发客户端全量重同步
UPDATE sync_root SET sync_cursor = 0 WHERE sync_cursor > 100000000000;
