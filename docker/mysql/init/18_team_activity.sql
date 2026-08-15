SET NAMES utf8mb4;
-- ============================================================
-- 团队空间活动日志表（st-team 模块）
-- ============================================================
CREATE TABLE IF NOT EXISTS team_activity (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '活动ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    space_id        BIGINT          NOT NULL                 COMMENT '空间ID',
    user_id         BIGINT          DEFAULT NULL             COMMENT '操作人ID',
    username        VARCHAR(100)    DEFAULT NULL             COMMENT '操作人用户名（冗余）',
    nickname        VARCHAR(100)    DEFAULT NULL             COMMENT '操作人昵称（冗余）',
    action          VARCHAR(50)     NOT NULL                 COMMENT '操作类型：FILE_UPLOAD/FILE_DELETE/MEMBER_JOIN/SPACE_UPDATE 等',
    target_type     VARCHAR(30)     DEFAULT NULL             COMMENT '目标类型：FILE/FOLDER/MEMBER/SPACE/INVITE',
    target_id       BIGINT          DEFAULT NULL             COMMENT '目标ID',
    target_name     VARCHAR(255)    DEFAULT NULL             COMMENT '目标名称',
    detail          TEXT            DEFAULT NULL             COMMENT '操作详情(JSON)',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_space_created (space_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队空间活动日志表';