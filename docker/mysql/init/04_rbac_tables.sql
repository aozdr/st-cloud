-- ========================================
-- RBAC 角色权限管理表
-- ========================================

-- 角色表（按租户隔离）
CREATE TABLE IF NOT EXISTS `sys_role` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`   BIGINT       NOT NULL DEFAULT 0,
    `role_code`   VARCHAR(64)  NOT NULL COMMENT '角色编码',
    `role_name`   VARCHAR(128) NOT NULL COMMENT '角色名称',
    `description` VARCHAR(256) DEFAULT NULL COMMENT '描述',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    `built_in`    TINYINT      NOT NULL DEFAULT 0 COMMENT '内置角色：0-否 1-是（不可删除）',
    `data_scope`  TINYINT      NOT NULL DEFAULT 1 COMMENT '数据范围：1-本人 2-租户 3-全部',
    `data`        JSON         DEFAULT NULL COMMENT '扩展数据（如限速配置）',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_role_code` (`tenant_id`, `role_code`, `deleted`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- 权限表（全局系统定义，不按租户隔离）
CREATE TABLE IF NOT EXISTS `sys_permission` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `permission_code` VARCHAR(128) NOT NULL COMMENT '权限编码（模块:操作）',
    `permission_name` VARCHAR(128) NOT NULL COMMENT '权限名称',
    `module`          VARCHAR(64)  NOT NULL COMMENT '所属模块',
    `description`     VARCHAR(256) DEFAULT NULL COMMENT '描述',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_permission_code` (`permission_code`),
    KEY `idx_module` (`module`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限定义表';

-- 用户-角色关联表（按租户隔离）
CREATE TABLE IF NOT EXISTS `sys_user_role` (
    `id`         BIGINT  NOT NULL AUTO_INCREMENT,
    `tenant_id`  BIGINT  NOT NULL DEFAULT 0,
    `user_id`    BIGINT  NOT NULL,
    `role_id`    BIGINT  NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`    TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_user_role` (`tenant_id`, `user_id`, `role_id`, `deleted`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色关联表';

-- 角色-权限关联表（按租户隔离）
CREATE TABLE IF NOT EXISTS `sys_role_permission` (
    `id`            BIGINT  NOT NULL AUTO_INCREMENT,
    `tenant_id`     BIGINT  NOT NULL DEFAULT 0,
    `role_id`       BIGINT  NOT NULL,
    `permission_id` BIGINT  NOT NULL,
    `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`       TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_role_perm` (`tenant_id`, `role_id`, `permission_id`, `deleted`),
    KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限关联表';

-- ========================================
-- 初始权限数据
-- ========================================
INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `module`, `description`) VALUES
-- 文件模块
('file:upload',       '文件上传',   'file',   '上传文件到云盘'),
('file:download',     '文件下载',   'file',   '下载云盘文件'),
('file:preview',     '文件预览',   'file',   '在线预览文件（图片/视频/文档等）'),
('file:delete',       '文件删除',   'file',   '删除文件或文件夹'),
('file:rename',       '文件重命名', 'file',   '重命名文件或文件夹'),
('file:move',         '文件移动',   'file',   '移动文件到其他目录'),
('file:copy',         '文件复制',   'file',   '复制文件或文件夹'),
('file:share',        '文件分享',   'file',   '创建文件分享链接'),
-- 分享模块
('share:create',      '创建分享',   'share',  '创建分享链接'),
('share:delete',      '删除分享',   'share',  '删除分享链接'),
('share:access',      '访问分享',   'share',  '访问他人分享的文件'),
-- 团队模块
('team:create',       '创建团队',   'team',   '创建团队空间'),
('team:manage',       '管理团队',   'team',   '管理团队成员和设置'),
('team:invite',       '邀请成员',   'team',   '邀请新成员加入团队'),
-- 搜索模块
('search:file',       '文件搜索',   'search', '搜索云盘文件'),
-- 管理模块
('admin:user:manage', '用户管理',   'admin',  '管理用户账号、配额、状态'),
('admin:role:manage', '角色管理',   'admin',  '管理角色和权限分配'),
('admin:audit:view',  '审计查看',   'admin',  '查看系统审计日志'),
('admin:stats:view',  '统计查看',   'admin',  '查看系统统计数据'),
('admin:storage:manage', '存储管理', 'admin',  '管理云盘总容量'),
-- 传输限速模块（预留）
('transfer:speed:limit', '传输限速', 'transfer', '配置上传/下载限速');

-- ========================================
-- 初始角色数据（默认租户 tenant_id=1；SAAS 模式受租户拦截器过滤）
-- ========================================
INSERT INTO `sys_role` (`tenant_id`, `role_code`, `role_name`, `description`, `status`, `built_in`, `data_scope`, `data`) VALUES
(1, 'admin', '系统管理员', '拥有系统全部权限', 1, 1, 3, NULL),
(1, 'user',  '普通用户',   '基础文件操作权限', 1, 1, 1, NULL);

-- ========================================
-- 角色-权限关联数据
-- ========================================
-- admin 角色（id=1）分配全部权限
INSERT INTO `sys_role_permission` (`tenant_id`, `role_id`, `permission_id`)
SELECT 1, 1, `id` FROM `sys_permission`;

-- user 角色（id=2）分配基础权限（排除 admin:* 和 transfer:speed:limit）
INSERT INTO `sys_role_permission` (`tenant_id`, `role_id`, `permission_id`)
SELECT 1, 2, `id` FROM `sys_permission`
WHERE `permission_code` NOT LIKE 'admin:%'
  AND `permission_code` != 'transfer:speed:limit';

-- ========================================
-- 为现有 admin 用户（id=1）分配 admin 角色（id=1）
-- ========================================
INSERT INTO `sys_user_role` (`tenant_id`, `user_id`, `role_id`) VALUES
(1, 1, 1);
