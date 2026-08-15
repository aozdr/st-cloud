SET NAMES utf8mb4;
-- 13_remove_ratelimit_orphan.sql
-- 清理历史遗留的孤儿权限码 admin:ratelimit:manage。
-- 该码原由 05_rate_limit_tables.sql 种入，但 SpeedLimitController 实际使用
-- transfer:speed:limit（已在 04_rbac_tables.sql 种子化）。admin:ratelimit:manage
-- 无任何控制器引用，属孤儿码，予以删除。
-- 幂等：新建库与存量库均可执行（不存在时为空操作）。
-- 关联文档：docs/PRD-用户权限系统.md

DROP PROCEDURE IF EXISTS stcloud_migrate_remove_ratelimit_orphan;
DELIMITER $$
CREATE PROCEDURE stcloud_migrate_remove_ratelimit_orphan()
BEGIN
    -- 1. 删除角色-权限关联（该码将彻底移除，物理删除避免悬空软删记录）
    DELETE rp FROM `sys_role_permission` rp
    INNER JOIN `sys_permission` p ON p.`id` = rp.`permission_id`
    WHERE p.`permission_code` = 'admin:ratelimit:manage';

    -- 2. 删除孤儿权限码（全局系统定义表，无租户隔离，无 deleted 列）
    DELETE FROM `sys_permission` WHERE `permission_code` = 'admin:ratelimit:manage';
END$$
DELIMITER ;
CALL stcloud_migrate_remove_ratelimit_orphan();
DROP PROCEDURE IF EXISTS stcloud_migrate_remove_ratelimit_orphan;