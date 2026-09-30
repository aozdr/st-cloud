SET NAMES utf8mb4;
-- ============================================================
-- 外部协作者字段 + 空间外部协作配置（st-team 模块）
-- 幂等加固（20260814-fix-m2m3）：重复执行不报错（information_schema 存在性守卫）
-- ============================================================

-- 幂等：仅当 member_type 列不存在时新增
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'team_member' AND COLUMN_NAME = 'member_type'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE team_member ADD COLUMN member_type TINYINT NOT NULL DEFAULT 0 COMMENT ''0-内部 1-外部''',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

-- 幂等：仅当 expire_at 列不存在时新增
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'team_member' AND COLUMN_NAME = 'expire_at'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE team_member ADD COLUMN expire_at DATETIME DEFAULT NULL COMMENT ''外部协作者有效期，NULL=永久''',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

CREATE TABLE IF NOT EXISTS team_external_config (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '配置ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    space_id        BIGINT          NOT NULL                 COMMENT '空间ID',
    allow_external  TINYINT         NOT NULL DEFAULT 0       COMMENT '是否允许外部协作者：0-否 1-是',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_space (space_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='空间外部协作配置表';
