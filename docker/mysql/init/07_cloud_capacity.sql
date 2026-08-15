SET NAMES utf8mb4;
-- ============================================================
-- 云盘总容量：个人与团队共享的物理存储上限
-- 幂等加固（20260814-fix-m2m3）：重复执行不报错（information_schema 存在性守卫）
-- ============================================================
USE stcloud;

-- 幂等：仅当 cloud_total_capacity 列不存在时新增
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_tenant' AND COLUMN_NAME = 'cloud_total_capacity'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE sys_tenant ADD COLUMN cloud_total_capacity BIGINT DEFAULT NULL COMMENT ''云盘总容量(字节)，NULL=不限'' AFTER default_quota',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

-- 默认总容量 100GB
UPDATE sys_tenant SET cloud_total_capacity = 107374182400 WHERE cloud_total_capacity IS NULL;
