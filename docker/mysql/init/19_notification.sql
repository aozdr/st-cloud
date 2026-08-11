-- ============================================================
-- 站内通知表（st-team 模块）
-- ============================================================
CREATE TABLE IF NOT EXISTS notification (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '通知ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    user_id         BIGINT          NOT NULL                 COMMENT '接收者ID',
    type            VARCHAR(20)     NOT NULL                 COMMENT '类型：MENTION/TEAM_INVITE/FILE_CHANGE/MEMBER_CHANGE',
    title           VARCHAR(200)    NOT NULL                 COMMENT '标题',
    content         VARCHAR(500)    DEFAULT NULL             COMMENT '正文',
    ref_type        VARCHAR(20)     DEFAULT NULL             COMMENT '关联类型：team/comment/file',
    ref_id          BIGINT          DEFAULT NULL             COMMENT '关联ID',
    `read` TINYINT         NOT NULL DEFAULT 0       COMMENT '0-未读 1-已读',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_user_read_created (user_id, `read`, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站内通知表';