-- ============================================================
-- 同步排除路径 + 冲突记录表（st-sync 模块）
-- ============================================================

-- 选择性同步：排除路径表（同步根下的子路径不同步）
CREATE TABLE IF NOT EXISTS sync_exclusion (
    id              BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '排除项ID',
    tenant_id       BIGINT       NOT NULL                 COMMENT '租户ID',
    sync_root_id    BIGINT       NOT NULL                 COMMENT '同步根ID',
    user_id         BIGINT       NOT NULL                 COMMENT '用户ID',
    relative_path   VARCHAR(1024) NOT NULL                COMMENT '相对同步根的路径（以 / 开头）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_root_path (sync_root_id, relative_path(765), deleted),
    KEY idx_root (sync_root_id, deleted),
    KEY idx_user (user_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='同步排除路径表';

-- 冲突记录表
CREATE TABLE IF NOT EXISTS sync_conflict (
    id              BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '冲突ID',
    tenant_id       BIGINT       NOT NULL                 COMMENT '租户ID',
    sync_root_id    BIGINT       NOT NULL                 COMMENT '同步根ID',
    user_id         BIGINT       NOT NULL                 COMMENT '用户ID',
    relative_path   VARCHAR(1024) NOT NULL                COMMENT '相对同步根的路径',
    local_md5       VARCHAR(64)  DEFAULT NULL             COMMENT '本地文件MD5',
    cloud_md5       VARCHAR(64)  DEFAULT NULL             COMMENT '云端文件MD5',
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT '状态：pending/resolved',
    resolution      VARCHAR(32)  DEFAULT NULL             COMMENT '解决方式：keep_both/server_wins/local_wins',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_root (sync_root_id, status, deleted),
    KEY idx_user (user_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='同步冲突记录表';