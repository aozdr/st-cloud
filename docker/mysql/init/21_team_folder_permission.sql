SET NAMES utf8mb4;
-- ============================================================
-- 团队文件夹权限表（st-team 模块）
-- ============================================================
CREATE TABLE IF NOT EXISTS team_folder_permission (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '权限记录ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    space_id        BIGINT          NOT NULL                 COMMENT '空间ID',
    folder_node_id  BIGINT          NOT NULL                 COMMENT '文件夹节点ID',
    subject_type    VARCHAR(10)     NOT NULL                 COMMENT '授权对象：role/member',
    subject_id      BIGINT          NOT NULL                 COMMENT '角色值或用户ID',
    permission      TINYINT         NOT NULL                 COMMENT '权限：-1-无权限 0-管理 1-编辑 2-查看',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_folder_subject (folder_node_id, subject_type, subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队文件夹权限表';