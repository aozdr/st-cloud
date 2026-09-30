SET NAMES utf8mb4;
-- ============================================================
-- file_share 增加 permissions（分享权限点 JSON）  TASK-PERM-DB
-- 权限模型重设计：permission 单值保留用于兼容，分享权限以 permissions JSON 为权威
-- 幂等：information_schema 存在性守卫，重复执行不报错（对齐 30 号脚本模式）
-- ============================================================

-- 幂等：仅当 permissions 列不存在时新增
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_share' AND COLUMN_NAME = 'permissions'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE file_share ADD COLUMN permissions VARCHAR(500) DEFAULT NULL COMMENT ''分享权限点JSON'' AFTER permission',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

-- 历史数据映射（仅对未设置过的行生效，重复执行不覆盖已配置数据）
-- permission=0（查看）
UPDATE file_share SET permissions = '{"view":true}'
WHERE permission = 0 AND permissions IS NULL;
-- permission=1（下载）
UPDATE file_share SET permissions = '{"view":true,"download":true}'
WHERE permission = 1 AND permissions IS NULL;
-- permission=2（上传）
UPDATE file_share SET permissions = '{"view":true,"upload":true}'
WHERE permission = 2 AND permissions IS NULL;
-- permission=3（编辑）：含 edit 编辑文档权限点
UPDATE file_share SET permissions = '{"view":true,"upload":true,"download":true,"delete":true,"rename":true,"move":true,"edit":true}'
WHERE permission = 3 AND permissions IS NULL;
