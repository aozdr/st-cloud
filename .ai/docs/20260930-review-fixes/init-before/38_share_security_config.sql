SET NAMES utf8mb4;
-- 38_share_security_config.sql
-- 全局配置表 + 分享防爆破默认参数 + admin:share:security 权限点。
-- 幂等：新建库与存量库均可执行。
-- 关联文档：.ai/docs/20260823-share-bruteforce/design.md

-- 1. 全局配置表（系统级，不按租户隔离，见 MyBatisPlusConfig IGNORE_TABLES）
CREATE TABLE IF NOT EXISTS `sys_config` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`    BIGINT       NOT NULL DEFAULT 0,
    `config_key`   VARCHAR(128) NOT NULL,
    `config_value` VARCHAR(512) DEFAULT NULL,
    `config_group` VARCHAR(64)  DEFAULT NULL,
    `remark`       VARCHAR(256) DEFAULT NULL,
    `enabled`      TINYINT      NOT NULL DEFAULT 1,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='全局配置表';

-- 2. 分享防爆破默认参数（首次插入，已存在则不覆盖）
INSERT IGNORE INTO `sys_config` (`tenant_id`, `config_key`, `config_value`, `config_group`, `remark`, `enabled`) VALUES
(0, 'share.brute_force.shareCodeLength', '12', 'share.brute_force.', '分享码长度（8~16）', 1),
(0, 'share.brute_force.maxFailPerCode',  '5',  'share.brute_force.', '单分享码失败阈值', 1),
(0, 'share.brute_force.codeWindowMs',    '300000', 'share.brute_force.', '单分享码失败窗口(ms)', 1),
(0, 'share.brute_force.codeLockMs',      '900000', 'share.brute_force.', '单分享码锁定(ms)', 1),
(0, 'share.brute_force.maxFailPerIp',    '20', 'share.brute_force.', '单IP总失败阈值', 1),
(0, 'share.brute_force.ipWindowMs',      '600000', 'share.brute_force.', '单IP失败窗口(ms)', 1),
(0, 'share.brute_force.ipLockMs',        '1800000', 'share.brute_force.', '单IP锁定(ms)', 1),
(0, 'share.brute_force.captchaEnabled',  'true', 'share.brute_force.', '是否启用验证码', 1),
(0, 'share.brute_force.captchaThreshold','3', 'share.brute_force.', '失败达阈值需验证码', 1),
(0, 'share.brute_force.captchaLockMs',   '1800000', 'share.brute_force.', '验证码校验失败锁定(ms)', 1);

-- 3. 新增权限点（若不存在）
INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `module`, `description`)
SELECT 'admin:share:security', '分享安全配置', 'admin', '配置分享防爆破与提取码安全参数'
WHERE NOT EXISTS (SELECT 1 FROM `sys_permission` WHERE `permission_code` = 'admin:share:security');

-- 4. admin 角色分配该权限（若未分配）
INSERT INTO `sys_role_permission` (`tenant_id`, `role_id`, `permission_id`)
SELECT r.tenant_id, r.id, p.id
FROM `sys_role` r
CROSS JOIN `sys_permission` p
WHERE r.`role_code` = 'admin' AND r.`deleted` = 0
  AND p.`permission_code` = 'admin:share:security'
  AND NOT EXISTS (
      SELECT 1 FROM `sys_role_permission` rp
      WHERE rp.`tenant_id` = r.`tenant_id` AND rp.`role_id` = r.`id`
        AND rp.`permission_id` = p.`id` AND rp.`deleted` = 0
  );
