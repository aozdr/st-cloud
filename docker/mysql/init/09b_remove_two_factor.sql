-- 09b_remove_two_factor.sql
-- 移除两步验证(TOTP)相关字段（功能已下线）
-- 关联文档：docs/PRD-云盘系统-v2.0.md Epic 8（安全与访问控制）；2FA 非需求范围，予以移除。
-- 幂等：列不存在时跳过，新建库与存量库均可安全执行。
-- 编号 09b（20260814-fix-m2m3）：09a=jwt_secret、09b=remove_2fa，按文件名顺序执行，消除同号歧义。

DROP PROCEDURE IF EXISTS stcloud_drop_2fa_cols;
DELIMITER $$
CREATE PROCEDURE stcloud_drop_2fa_cols()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND column_name = 'two_factor_enabled') THEN
        ALTER TABLE sys_user DROP COLUMN two_factor_enabled;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sys_user' AND column_name = 'two_factor_secret') THEN
        ALTER TABLE sys_user DROP COLUMN two_factor_secret;
    END IF;
END$$
DELIMITER ;
CALL stcloud_drop_2fa_cols();
DROP PROCEDURE IF EXISTS stcloud_drop_2fa_cols;
