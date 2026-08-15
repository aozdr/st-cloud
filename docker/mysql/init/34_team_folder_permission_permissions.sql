SET NAMES utf8mb4;
-- ============================================================
-- team_folder_permission 增加 permissions（权限点 JSON）  TASK-PERM-DB
-- 权限模型重设计：单值 permission 保留用于迁移/兼容，权限计算以 permissions JSON 为权威
-- 幂等：information_schema 存在性守卫，重复执行不报错（对齐 30 号脚本模式）
-- ============================================================

-- 幂等：仅当 permissions 列不存在时新增
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'team_folder_permission' AND COLUMN_NAME = 'permissions'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE team_folder_permission ADD COLUMN permissions VARCHAR(500) DEFAULT NULL COMMENT ''权限点JSON：{"view":true,"upload":true,...}'' AFTER permission',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

-- 历史数据映射（仅对未设置过的行生效，重复执行不覆盖已配置数据）
-- permission=-1（无权限）：仅标注，规则语义为增强，-1 不再生效
UPDATE team_folder_permission SET permissions = '{"view":false}'
WHERE permission = -1 AND permissions IS NULL;
-- permission=0（管理）：全部 9 个权限点 true
UPDATE team_folder_permission SET permissions = '{"view":true,"upload":true,"download":true,"delete":true,"rename":true,"move":true,"share":true,"manage_members":true,"manage_settings":true}'
WHERE permission = 0 AND permissions IS NULL;
-- permission=1（编辑）：内容操作权限点 true，空间管理权限点 false
UPDATE team_folder_permission SET permissions = '{"view":true,"upload":true,"download":true,"delete":true,"rename":true,"move":true,"share":true,"manage_members":false,"manage_settings":false}'
WHERE permission = 1 AND permissions IS NULL;
-- permission=2（查看）：仅可查看
UPDATE team_folder_permission SET permissions = '{"view":true}'
WHERE permission = 2 AND permissions IS NULL;
