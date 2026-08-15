SET NAMES utf8mb4;
-- ============================================================
-- 同步引擎表（st-sync 模块）
-- ============================================================

CREATE TABLE IF NOT EXISTS sync_root (
    id                  BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '同步根ID',
    tenant_id           BIGINT          NOT NULL                 COMMENT '租户ID',
    user_id             BIGINT          NOT NULL                 COMMENT '所属用户ID',
    cloud_folder_node_id BIGINT         NOT NULL                 COMMENT '云端文件夹节点ID（file_node.id）',
    local_path_hint     VARCHAR(500)   DEFAULT NULL             COMMENT '本地路径提示（客户端实际路径存本地）',
    status              TINYINT        NOT NULL DEFAULT 0       COMMENT '状态：0-启用 1-暂停',
    sync_cursor         BIGINT         NOT NULL DEFAULT 0       COMMENT '上次同步游标（epoch ms）',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted             TINYINT        NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_user (user_id, deleted),
    KEY idx_tenant (tenant_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='同步根配置表';