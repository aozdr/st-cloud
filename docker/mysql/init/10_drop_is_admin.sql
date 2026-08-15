SET NAMES utf8mb4;
-- 10_drop_is_admin.sql
-- 移除 sys_user.is_admin 魔法值列：管理员身份统一由 RBAC admin 角色表达。
-- 关联文档：docs/PRD-用户权限系统.md US1
-- 幂等：先将 is_admin=1 的用户补配 admin 角色，再安全删除列；新建库与存量库均可执行。

DROP PROCEDURE IF EXISTS stcloud_migrate_is_admin;
DELIMITER $$
CREATE PROCEDURE stcloud_migrate_is_admin()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND column_name = 'is_admin') THEN

        -- 为 is_admin=1 的用户补配 admin 角色（已分配则跳过）
        INSERT INTO sys_user_role (tenant_id, user_id, role_id)
        SELECT u.tenant_id, u.id, r.id
        FROM sys_user u
        JOIN sys_role r ON r.role_code = 'admin' AND r.deleted = 0
        WHERE u.is_admin = 1
          AND NOT EXISTS (
              SELECT 1 FROM sys_user_role ur
              WHERE ur.user_id = u.id AND ur.role_id = r.id AND ur.deleted = 0
          );

        ALTER TABLE sys_user DROP COLUMN is_admin;
    END IF;
END$$
DELIMITER ;
CALL stcloud_migrate_is_admin();
DROP PROCEDURE IF EXISTS stcloud_migrate_is_admin;