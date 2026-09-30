SET NAMES utf8mb4;
-- ============================================================
-- 39_reset_editing_permission.sql
-- 新增权限码 file:reset-editing（重置编辑中状态）
-- 用于强制解除文件「编辑中」占用标记（editor:active:{nodeId}）。
-- 仅授予 admin 角色；需要该能力的其它角色可由管理员在角色管理分配。
-- 幂等：新建库与存量库均可重复执行。
-- ============================================================

DROP PROCEDURE IF EXISTS stcloud_migrate_add_reset_editing_permission;
DELIMITER $$
CREATE PROCEDURE stcloud_migrate_add_reset_editing_permission()
BEGIN
    -- 1. 新增权限码（若不存在）
    INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `module`, `description`)
    SELECT 'file:reset-editing', '重置编辑状态', 'file', '强制解除文件「编辑中」占用标记'
    WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `permission_code` = 'file:reset-editing');

    -- 2. admin 角色分配（若未分配）
    INSERT INTO `sys_role_permission` (`tenant_id`, `role_id`, `permission_id`)
    SELECT r.tenant_id, r.id, p.id
    FROM `sys_role` r
    CROSS JOIN `sys_permission` p
    WHERE r.`role_code` = 'admin' AND r.`deleted` = 0
      AND p.`permission_code` = 'file:reset-editing'
      AND NOT EXISTS (
          SELECT 1 FROM `sys_role_permission` rp
          WHERE rp.`tenant_id` = r.`tenant_id` AND rp.`role_id` = r.`id`
            AND rp.`permission_id` = p.`id` AND rp.`deleted` = 0
      );
END$$
DELIMITER ;
CALL stcloud_migrate_add_reset_editing_permission();
DROP PROCEDURE IF EXISTS stcloud_migrate_add_reset_editing_permission;
