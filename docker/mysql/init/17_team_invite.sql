-- ============================================================
-- 团队空间邀请链接表（st-team 模块）
-- ============================================================
CREATE TABLE IF NOT EXISTS team_invite (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '邀请ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    space_id        BIGINT          NOT NULL                 COMMENT '空间ID',
    invite_code     VARCHAR(32)     NOT NULL                 COMMENT '邀请码（32位随机串）',
    role            TINYINT         NOT NULL DEFAULT 2       COMMENT '默认角色：0-管理员 1-编辑者 2-查看者',
    created_by      BIGINT          NOT NULL                 COMMENT '创建者ID',
    expire_at       DATETIME        DEFAULT NULL             COMMENT '过期时间，NULL=永久',
    status          TINYINT         NOT NULL DEFAULT 1       COMMENT '状态：0-已撤销 1-有效',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_invite_code (invite_code),
    KEY idx_space_status (space_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队空间邀请链接表';