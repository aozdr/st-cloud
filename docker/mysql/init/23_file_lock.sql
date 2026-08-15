-- ============================================================
-- 文件锁定字段（st-core 模块）
-- 幂等加固（20260814-fix-m2m3）：重复执行不报错（information_schema 存在性守卫）
-- ============================================================

-- 幂等：仅当 locked_by 列不存在时新增
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_node' AND COLUMN_NAME = 'locked_by'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE file_node ADD COLUMN locked_by BIGINT DEFAULT NULL COMMENT ''锁定人ID，NULL=未锁定''',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

-- 幂等：仅当 locked_at 列不存在时新增
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_node' AND COLUMN_NAME = 'locked_at'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE file_node ADD COLUMN locked_at DATETIME DEFAULT NULL COMMENT ''锁定时间''',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

-- 幂等：仅当 lock_expire_at 列不存在时新增
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_node' AND COLUMN_NAME = 'lock_expire_at'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE file_node ADD COLUMN lock_expire_at DATETIME DEFAULT NULL COMMENT ''锁定过期时间，NULL=永久''',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;
