SET NAMES utf8mb4;
-- 14_add_preview_permission.sql
-- 新增权限码 file:preview（文件预览），用于控制在线预览文件的可见性。
-- 预览与下载分离：仅有 file:preview 的用户可在线查看，但不能下载。
-- 幂等：新建库与存量库均可执行。
-- 关联文档：docs/PRD-用户权限系统.md

DROP PROCEDURE IF EXISTS stcloud_migrate_add_preview_permission;
DELIMITER $$
CREATE PROCEDURE stcloud_migrate_add_preview_permission()
BEGIN
    -- 1. 新增权限码（若不存在）
    INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `module`, `description`)
    SELECT 'file:preview', '文件预览', 'file', '在线预览文件（图片/视频/文档等）'
    WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `permission_code` = 'file:preview');

    -- 2. admin + user 角色分配 file:preview（若未分配）
    INSERT INTO `sys_role_permission` (`tenant_id`, `role_id`, `permission_id`)
    SELECT r.tenant_id, r.id, p.id
    FROM `sys_role` r
    CROSS JOIN `sys_permission` p
    WHERE r.`role_code` IN ('admin', 'user') AND r.`deleted` = 0
      AND p.`permission_code` = 'file:preview'
      AND NOT EXISTS (
          SELECT 1 FROM `sys_role_permission` rp
          WHERE rp.`tenant_id` = r.`tenant_id` AND rp.`role_id` = r.`id`
            AND rp.`permission_id` = p.`id` AND rp.`deleted` = 0
      );
END$$
DELIMITER ;
CALL stcloud_migrate_add_preview_permission();
DROP PROCEDURE IF EXISTS stcloud_migrate_add_preview_permission;