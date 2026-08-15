-- ============================================================
-- 文件加密隐藏字段（st-core 模块）
-- 幂等加固（20260814-fix-m2m3）：重复执行不报错（information_schema 存在性守卫）
-- ============================================================

-- 幂等：仅当 hidden 列不存在时新增
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_node' AND COLUMN_NAME = 'hidden'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE file_node ADD COLUMN hidden TINYINT NOT NULL DEFAULT 0 COMMENT ''是否隐藏：0-正常 1-隐藏''',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;
