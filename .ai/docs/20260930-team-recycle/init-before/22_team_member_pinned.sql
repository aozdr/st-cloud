SET NAMES utf8mb4;
-- ============================================================
-- 团队成员置顶字段（st-team 模块）
-- 幂等加固（20260814-fix-m2m3）：重复执行不报错（information_schema 存在性守卫）
-- ============================================================

-- 幂等：仅当 is_pinned 列不存在时新增
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'team_member' AND COLUMN_NAME = 'is_pinned'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE team_member ADD COLUMN is_pinned TINYINT NOT NULL DEFAULT 0 COMMENT ''是否置顶：0-否 1-是''',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;
