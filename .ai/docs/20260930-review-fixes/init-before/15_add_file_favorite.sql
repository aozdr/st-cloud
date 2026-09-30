SET NAMES utf8mb4;
-- ============================================================
-- 文件收藏表（st-core 模块）
-- ============================================================

CREATE TABLE IF NOT EXISTS file_favorite (
    id            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id     BIGINT   NOT NULL                COMMENT '租户ID',
    user_id       BIGINT   NOT NULL                COMMENT '收藏者用户ID',
    file_node_id  BIGINT   NOT NULL                COMMENT '被收藏的文件节点ID',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT  NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_node (user_id, file_node_id, deleted),
    KEY idx_user_id (user_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件收藏表';