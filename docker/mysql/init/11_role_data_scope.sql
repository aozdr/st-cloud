-- 11_role_data_scope.sql
-- 为 sys_role 增加 data_scope（数据范围）字段，替代散落的 ownerId 越权旁路。
-- 数据范围：1-本人（仅自有资源） 2-租户（本租户资源） 3-全部（跨租户/跨所有者）
-- 注意：内置 admin/user 角色保留 tenant_id=1（默认租户），受租户拦截器过滤；
--       不改为 tenant_id=0，否则 SAAS 模式下租户用户查不到内置角色、登录失败。
-- 关联文档：docs/PRD-用户权限系统.md 第10节 Q1/Q2 决策
-- 幂等：新建库与存量库均可执行。

DROP PROCEDURE IF EXISTS stcloud_migrate_role_data_scope;
DELIMITER $$
CREATE PROCEDURE stcloud_migrate_role_data_scope()
BEGIN
    -- 1. 新增 data_scope 列（若不存在）
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'sys_role' AND column_name = 'data_scope') THEN
        ALTER TABLE sys_role ADD COLUMN data_scope TINYINT NOT NULL DEFAULT 1 COMMENT '数据范围：1-本人 2-租户 3-全部';
    END IF;

    -- 2. 内置 admin 角色：数据范围=全部(3)
    UPDATE sys_role SET data_scope = 3 WHERE role_code = 'admin' AND deleted = 0;

    -- 3. 内置 user 角色：数据范围=本人(1)
    UPDATE sys_role SET data_scope = 1 WHERE role_code = 'user' AND deleted = 0;
END$$
DELIMITER ;
CALL stcloud_migrate_role_data_scope();
DROP PROCEDURE IF EXISTS stcloud_migrate_role_data_scope;