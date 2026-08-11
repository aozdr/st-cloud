-- ============================================================
-- 团队空间文件评论表（st-team 模块）
-- ============================================================
CREATE TABLE IF NOT EXISTS team_comment (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '评论ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    space_id        BIGINT          NOT NULL                 COMMENT '空间ID',
    node_id         BIGINT          NOT NULL                 COMMENT '文件节点ID',
    user_id         BIGINT          NOT NULL                 COMMENT '评论人ID',
    content         TEXT            NOT NULL                 COMMENT '评论内容',
    parent_id       BIGINT          DEFAULT NULL             COMMENT '父评论ID（NULL=顶级）',
    mentions        VARCHAR(500)    DEFAULT NULL             COMMENT '@提及用户ID列表(逗号分隔)',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_node_created (node_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队空间文件评论表';