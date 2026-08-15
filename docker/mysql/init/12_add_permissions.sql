SET NAMES utf8mb4;
-- 12_add_permissions.sql
-- 新增权限码 file:copy、admin:storage:manage，并分配给对应角色。
-- file:copy -> admin + user 角色；admin:storage:manage -> 仅 admin 角色。
-- 幂等：新建库与存量库均可执行。
-- 关联文档：docs/PRD-用户权限系统.md

DROP PROCEDURE IF EXISTS stcloud_migrate_add_permissions;
DELIMITER $$
CREATE PROCEDURE stcloud_migrate_add_permissions()
BEGIN
    -- 1. 新增权限码（若不存在）
    INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `module`, `description`)
    SELECT 'file:copy', '文件复制', 'file', '复制文件或文件夹'
    WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `permission_code` = 'file:copy');

    INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `module`, `description`)
    SELECT 'admin:storage:manage', '存储管理', 'admin', '管理云盘总容量'
    WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `permission_code` = 'admin:storage:manage');

    -- 2. admin 角色分配两个新权限码（若未分配）
    INSERT INTO `sys_role_permission` (`tenant_id`, `role_id`, `permission_id`)
    SELECT r.tenant_id, r.id, p.id
    FROM `sys_role` r
    CROSS JOIN `sys_permission` p
    WHERE r.`role_code` = 'admin' AND r.`deleted` = 0
      AND p.`permission_code` IN ('file:copy', 'admin:storage:manage')
      AND NOT EXISTS (
          SELECT 1 FROM `sys_role_permission` rp
          WHERE rp.`tenant_id` = r.`tenant_id` AND rp.`role_id` = r.`id`
            AND rp.`permission_id` = p.`id` AND rp.`deleted` = 0
      );

    -- 3. user 角色仅分配 file:copy（不分配 admin:storage:manage）
    INSERT INTO `sys_role_permission` (`tenant_id`, `role_id`, `permission_id`)
    SELECT r.tenant_id, r.id, p.id
    FROM `sys_role` r
    CROSS JOIN `sys_permission` p
    WHERE r.`role_code` = 'user' AND r.`deleted` = 0
      AND p.`permission_code` = 'file:copy'
      AND NOT EXISTS (
          SELECT 1 FROM `sys_role_permission` rp
          WHERE rp.`tenant_id` = r.`tenant_id` AND rp.`role_id` = r.`id`
            AND rp.`permission_id` = p.`id` AND rp.`deleted` = 0
      );
END$$
DELIMITER ;
CALL stcloud_migrate_add_permissions();
DROP PROCEDURE IF EXISTS stcloud_migrate_add_permissions;