-- ============================================================
-- st-auth 集成测试用 H2 兼容建表脚本（认证授权模块）
-- 列定义对照 docker/mysql/init/02_create_tables.sql、
-- 04_rbac_tables.sql、09_jwt_secret.sql
-- 去除 ENGINE/CHARSET/COLLATE/COMMENT（H2 不支持或不需要）
-- ============================================================

-- 租户表
CREATE TABLE IF NOT EXISTS sys_tenant (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_name          VARCHAR(100) NOT NULL,
    tenant_code          VARCHAR(50)  NOT NULL,
    domain               VARCHAR(255) DEFAULT NULL,
    status               TINYINT      NOT NULL DEFAULT 1,
    default_quota        BIGINT       NOT NULL DEFAULT 10737418240,
    cloud_total_capacity BIGINT       DEFAULT NULL,
    expire_at            DATETIME     DEFAULT NULL,
    tenant_id            BIGINT       DEFAULT NULL,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted              TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_code UNIQUE (tenant_code)
);

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NOT NULL,
    username       VARCHAR(50)  NOT NULL,
    password       VARCHAR(255) NOT NULL,
    nickname       VARCHAR(100) DEFAULT NULL,
    email          VARCHAR(100) DEFAULT NULL,
    phone          VARCHAR(20)  DEFAULT NULL,
    avatar         VARCHAR(500) DEFAULT NULL,
    status         TINYINT      NOT NULL DEFAULT 1,
    storage_used   BIGINT       NOT NULL DEFAULT 0,
    storage_quota  BIGINT       DEFAULT NULL,
    last_login_at  DATETIME     DEFAULT NULL,
    last_login_ip  VARCHAR(50)  DEFAULT NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_username UNIQUE (tenant_id, username)
);

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL DEFAULT 0,
    role_code   VARCHAR(64)  NOT NULL,
    role_name   VARCHAR(128) NOT NULL,
    description VARCHAR(256) DEFAULT NULL,
    status      TINYINT      NOT NULL DEFAULT 1,
    built_in    TINYINT      NOT NULL DEFAULT 0,
    data_scope  TINYINT      NOT NULL DEFAULT 1,
    data        TEXT         DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_role_code UNIQUE (tenant_id, role_code, deleted)
);

-- 权限表（全局系统定义，不按租户隔离）
CREATE TABLE IF NOT EXISTS sys_permission (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    permission_code VARCHAR(128) NOT NULL,
    permission_name VARCHAR(128) NOT NULL,
    module          VARCHAR(64)  NOT NULL,
    description     VARCHAR(256) DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_permission_code UNIQUE (permission_code)
);

-- 用户-角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT   NOT NULL DEFAULT 0,
    user_id    BIGINT   NOT NULL,
    role_id    BIGINT   NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted    TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_user_role UNIQUE (tenant_id, user_id, role_id, deleted)
);

-- 角色-权限关联表
CREATE TABLE IF NOT EXISTS sys_role_permission (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT   NOT NULL DEFAULT 0,
    role_id       BIGINT   NOT NULL,
    permission_id BIGINT   NOT NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_role_perm UNIQUE (tenant_id, role_id, permission_id, deleted)
);

-- JWT 签名密钥表（AES-GCM 加密存储）
CREATE TABLE IF NOT EXISTS sys_jwt_secret (
    id                BIGINT       NOT NULL,
    config_key        VARCHAR(64)  NOT NULL,
    secret_ciphertext VARCHAR(512) NOT NULL,
    secret_iv         VARCHAR(64)  NOT NULL,
    tenant_id         BIGINT       DEFAULT NULL,
    created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_config_key UNIQUE (config_key)
);

-- ============================================================
-- 初始数据（对照 docker/mysql/init/ 02/04 脚本，仅保留测试所需）
-- ============================================================

-- 默认租户
INSERT INTO sys_tenant (id, tenant_name, tenant_code, status, default_quota)
VALUES (1, '默认租户', 'default', 1, 10737418240);

-- 默认管理员 (密码: admin123, BCrypt加密，与 docker init 一致)
INSERT INTO sys_user (id, tenant_id, username, password, nickname, status, storage_used, storage_quota)
VALUES (1, 1, 'admin', '$2b$10$mLMRBZxVo/uyk0EvmfvC9eCUPOFCcs4EA38LYI4gmq8oHfJe6ALWW', '管理员', 1, 0, 10737418240);

-- 内置角色：admin / user
INSERT INTO sys_role (id, tenant_id, role_code, role_name, description, status, built_in, data_scope, data) VALUES
(1, 1, 'admin', '系统管理员', '拥有系统全部权限', 1, 1, 3, NULL),
(2, 1, 'user',  '普通用户',   '基础文件操作权限', 1, 1, 1, NULL);

-- 权限定义（auth/admin 主路径相关子集）
INSERT INTO sys_permission (id, permission_code, permission_name, module, description) VALUES
(1, 'file:upload',          '文件上传',   'file',     '上传文件到云盘'),
(2, 'file:download',        '文件下载',   'file',     '下载云盘文件'),
(3, 'file:preview',         '文件预览',   'file',     '在线预览文件'),
(4, 'share:create',         '创建分享',   'share',    '创建分享链接'),
(5, 'admin:user:manage',    '用户管理',   'admin',    '管理用户账号、配额、状态'),
(6, 'admin:role:manage',    '角色管理',   'admin',    '管理角色和权限分配'),
(7, 'admin:audit:view',     '审计查看',   'admin',    '查看系统审计日志'),
(8, 'admin:stats:view',     '统计查看',   'admin',    '查看系统统计数据'),
(9, 'transfer:speed:limit', '传输限速',   'transfer', '配置上传/下载限速');

-- 角色-权限关联：admin 全部权限；user 基础文件权限
INSERT INTO sys_role_permission (tenant_id, role_id, permission_id) VALUES
(1, 1, 1), (1, 1, 2), (1, 1, 3), (1, 1, 4), (1, 1, 5), (1, 1, 6), (1, 1, 7), (1, 1, 8), (1, 1, 9),
(1, 2, 1), (1, 2, 2), (1, 2, 4);

-- admin 用户（id=1）分配 admin 角色（id=1）
INSERT INTO sys_user_role (tenant_id, user_id, role_id) VALUES (1, 1, 1);
