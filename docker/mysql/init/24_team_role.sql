SET NAMES utf8mb4;
-- ============================================================
-- 团队自定义角色表（st-team 模块）
-- ============================================================
CREATE TABLE IF NOT EXISTS team_role (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '角色ID（>=100）',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    space_id        BIGINT          NOT NULL                 COMMENT '空间ID',
    name            VARCHAR(50)     NOT NULL                 COMMENT '角色名称',
    permissions     VARCHAR(500)    NOT NULL                 COMMENT '权限JSON',
    status          TINYINT         NOT NULL DEFAULT 1       COMMENT '0-停用 1-启用',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_space (space_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队自定义角色表';