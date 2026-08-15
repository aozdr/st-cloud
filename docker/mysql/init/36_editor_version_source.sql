-- ============================================================
-- file_version 增加 source（版本来源） TASK-20260815-onlyoffice-editor-01
-- 0=上传覆盖产生；1=在线编辑器保存产生（D1：仅编辑器版本参与 20 条上限裁剪）
-- 幂等：information_schema 存在性守卫，重复执行不报错（对齐 30 号脚本模式）
-- ============================================================

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_version' AND COLUMN_NAME = 'source'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE file_version ADD COLUMN source TINYINT NOT NULL DEFAULT 0 COMMENT ''版本来源：0-上传覆盖 1-编辑器保存'' AFTER modifier_name',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;
