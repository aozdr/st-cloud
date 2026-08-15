-- 替换上传按差值计费：file_chunk 记录原文件大小
-- 幂等加固（20260814-fix-m2m3）：重复执行不报错（information_schema 存在性守卫）
USE stcloud;

-- 幂等：仅当 original_size 列不存在时新增
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_chunk' AND COLUMN_NAME = 'original_size'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE file_chunk ADD COLUMN original_size BIGINT DEFAULT NULL COMMENT ''替换上传时原文件大小(字节)，用于合并时按差值计费'' AFTER storage_path',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;
