-- ========================================
-- 传输限速规则表
-- ========================================

CREATE TABLE IF NOT EXISTS `sys_rate_limit` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`            BIGINT       NOT NULL DEFAULT 0,
    `rule_name`            VARCHAR(128) NOT NULL COMMENT '规则名称',
    `scope`                TINYINT      NOT NULL COMMENT '0-按用户 1-按角色',
    `target_id`            BIGINT       NOT NULL COMMENT '用户ID 或 角色ID',
    `target_code`          VARCHAR(64)  DEFAULT NULL COMMENT '匹配标识:角色编码(role) / 用户名(user)',
    `target_name`          VARCHAR(128) DEFAULT NULL COMMENT '展示名:昵称 / 角色名',
    `upload_speed_limit`   INT          NOT NULL DEFAULT 0 COMMENT '上传限速 KB/s,0=不限速',
    `download_speed_limit` INT          NOT NULL DEFAULT 0 COMMENT '下载限速 KB/s,0=不限速',
    `enabled`              TINYINT      NOT NULL DEFAULT 1 COMMENT '0-禁用 1-启用',
    `description`          VARCHAR(256) DEFAULT NULL,
    `created_at`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`              TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_scope_target` (`scope`, `target_id`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='传输限速规则表';

-- ========================================
-- 限速管理权限码
-- ========================================
INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `module`, `description`) VALUES
('admin:ratelimit:manage', '限速管理', 'admin', '配置用户/角色上传下载速度限速规则');

-- 为 admin 角色(id=1)分配限速管理权限(默认租户 tenant_id=1)
INSERT INTO `sys_role_permission` (`tenant_id`, `role_id`, `permission_id`)
SELECT 1, 1, `id` FROM `sys_permission` WHERE `permission_code` = 'admin:ratelimit:manage';