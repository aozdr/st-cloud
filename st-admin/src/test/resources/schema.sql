-- ============================================================
-- st-admin 集成测试用 H2 兼容建表脚本（审计日志 + 传输限速）
-- 列定义对照 docker/mysql/init/02_create_tables.sql、
-- 05_rate_limit_tables.sql
-- 去除 ENGINE/CHARSET/COLLATE/COMMENT（H2 不支持或不需要）
-- ============================================================

-- 租户表（sys_tenant 属于系统级表，租户拦截器忽略）
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

-- 传输限速规则表
CREATE TABLE IF NOT EXISTS sys_rate_limit (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id            BIGINT       NOT NULL DEFAULT 0,
    rule_name            VARCHAR(128) NOT NULL,
    scope                TINYINT      NOT NULL,
    target_id            BIGINT       NOT NULL,
    target_code          VARCHAR(64)  DEFAULT NULL,
    target_name          VARCHAR(128) DEFAULT NULL,
    upload_speed_limit   INT          NOT NULL DEFAULT 0,
    download_speed_limit INT          NOT NULL DEFAULT 0,
    enabled              TINYINT      NOT NULL DEFAULT 1,
    description          VARCHAR(256) DEFAULT NULL,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted              TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

-- 审计日志表
CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    user_id     BIGINT       DEFAULT NULL,
    username    VARCHAR(100) DEFAULT NULL,
    action      VARCHAR(50)  NOT NULL,
    target_type VARCHAR(30)  DEFAULT NULL,
    target_id   BIGINT       DEFAULT NULL,
    target_name VARCHAR(255) DEFAULT NULL,
    detail      TEXT         DEFAULT NULL,
    ip_address  VARCHAR(50)  DEFAULT NULL,
    user_agent  VARCHAR(500) DEFAULT NULL,
    status      TINYINT      NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_audit_tenant_user ON audit_log (tenant_id, user_id);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_log (action);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log (created_at);
CREATE INDEX IF NOT EXISTS idx_ratelimit_scope_target ON sys_rate_limit (scope, target_id);
CREATE INDEX IF NOT EXISTS idx_ratelimit_tenant ON sys_rate_limit (tenant_id);

-- 默认租户
INSERT INTO sys_tenant (id, tenant_name, tenant_code, status, default_quota)
VALUES (1, '默认租户', 'default', 1, 10737418240);
