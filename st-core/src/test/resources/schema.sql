-- ============================================================
-- 测试用 H2 兼容建表脚本（仅收藏功能测试所需表）
-- 从 docker/mysql/init/02_create_tables.sql 和 15_add_file_favorite.sql 转换
-- 去除 ENGINE/CHARSET/COLLATE/COMMENT（H2 不支持或不需要）
-- ============================================================

CREATE TABLE IF NOT EXISTS file_node (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    parent_id       BIGINT          NOT NULL DEFAULT 0,
    node_type       TINYINT         NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    path            VARCHAR(1000)   NOT NULL DEFAULT '/',
    file_size       BIGINT          NOT NULL DEFAULT 0,
    file_md5        VARCHAR(64)     DEFAULT NULL,
    content_type    VARCHAR(100)    DEFAULT NULL,
    suffix          VARCHAR(20)     DEFAULT NULL,
    storage_path    VARCHAR(500)    DEFAULT NULL,
    object_id       BIGINT          DEFAULT NULL,
    status          TINYINT         NOT NULL DEFAULT 0,
    upload_status   TINYINT         NOT NULL DEFAULT 0,
    uploader_id     BIGINT          NOT NULL,
    owner_id        BIGINT          NOT NULL,
    space_id        BIGINT          DEFAULT NULL,
    ref_count       INT             NOT NULL DEFAULT 1,
    version         INT             NOT NULL DEFAULT 0,
    thumbnail_path  VARCHAR(500)    DEFAULT NULL,
    hidden          TINYINT         NOT NULL DEFAULT 0,
    locked_by       BIGINT          DEFAULT NULL,
    locked_at       DATETIME        DEFAULT NULL,
    lock_expire_at  DATETIME        DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS file_favorite (
    id            BIGINT    NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT    NOT NULL,
    user_id       BIGINT    NOT NULL,
    file_node_id  BIGINT    NOT NULL,
    created_at    DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       TINYINT   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_node UNIQUE (user_id, file_node_id, deleted)
);

CREATE INDEX IF NOT EXISTS idx_fn_owner ON file_node (owner_id, deleted);
CREATE INDEX IF NOT EXISTS idx_ff_user ON file_favorite (user_id, deleted);

-- ============================================================
-- 文件对象表（去重/引用计数，TASK-001）
-- ============================================================
CREATE TABLE IF NOT EXISTS file_object (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    md5           VARCHAR(64)  NOT NULL,
    size          BIGINT       NOT NULL DEFAULT 0,
    storage_path  VARCHAR(500) NOT NULL,
    ref_count     INT          NOT NULL DEFAULT 0,
    status        TINYINT      NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_md5 UNIQUE (tenant_id, md5)
);
-- ============================================================
-- 上传状态机/配额/版本 测试所需表（TASK-002）
-- 从 docker/mysql/init/02_create_tables.sql 转换（去除 COMMENT/ENGINE/CHARSET/COLLATE）
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_tenant (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_name     VARCHAR(100) NOT NULL,
    tenant_code     VARCHAR(50)  NOT NULL,
    domain          VARCHAR(255) DEFAULT NULL,
    status          TINYINT      NOT NULL DEFAULT 1,
    default_quota   BIGINT       NOT NULL DEFAULT 10737418240,
    cloud_total_capacity BIGINT   DEFAULT NULL,
    expire_at       DATETIME     DEFAULT NULL,
    tenant_id       BIGINT       DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    username        VARCHAR(50)  NOT NULL,
    password        VARCHAR(255) NOT NULL,
    nickname        VARCHAR(100) DEFAULT NULL,
    email           VARCHAR(100) DEFAULT NULL,
    phone           VARCHAR(20)  DEFAULT NULL,
    avatar          VARCHAR(500) DEFAULT NULL,
    status          TINYINT      NOT NULL DEFAULT 1,
    storage_used    BIGINT       NOT NULL DEFAULT 0,
    storage_quota   BIGINT       DEFAULT NULL,
    last_login_at   DATETIME     DEFAULT NULL,
    last_login_ip   VARCHAR(50)  DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS file_chunk (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    upload_id       VARCHAR(200) NOT NULL,
    file_node_id    BIGINT       NOT NULL,
    chunk_index     INT          NOT NULL,
    chunk_size      BIGINT       NOT NULL,
    chunk_md5       VARCHAR(64)  DEFAULT NULL,
    storage_path    VARCHAR(500) DEFAULT NULL,
    original_size   BIGINT       DEFAULT NULL,
    status          TINYINT      NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_upload_chunk UNIQUE (upload_id, chunk_index)
);

CREATE TABLE IF NOT EXISTS file_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    file_node_id    BIGINT       NOT NULL,
    version_num     INT          NOT NULL,
    file_size       BIGINT       NOT NULL,
    file_md5        VARCHAR(64)  NOT NULL,
    storage_path    VARCHAR(500) NOT NULL,
    modifier_id     BIGINT       NOT NULL,
    modifier_name   VARCHAR(100) DEFAULT NULL,
    source          TINYINT      NOT NULL DEFAULT 0 COMMENT '0-上传覆盖 1-编辑器保存',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS team_space (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    space_name      VARCHAR(100) NOT NULL,
    description     VARCHAR(500) DEFAULT NULL,
    icon            VARCHAR(500) DEFAULT NULL,
    owner_id        BIGINT       NOT NULL,
    storage_used    BIGINT       NOT NULL DEFAULT 0,
    storage_quota   BIGINT       DEFAULT NULL,
    status          TINYINT      NOT NULL DEFAULT 1,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
-- ============================================================
-- 事件 Outbox 表（TASK-004）
-- ============================================================
CREATE TABLE IF NOT EXISTS event_log (
    id            BIGINT       NOT NULL,
    tenant_id     BIGINT       DEFAULT NULL,
    event_type    VARCHAR(32)  NOT NULL,
    payload       CLOB         NOT NULL,
    status        TINYINT      NOT NULL DEFAULT 0,
    retry_count   INT          NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at  DATETIME     DEFAULT NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_status (status, retry_count, created_at)
);
-- ============================================================
-- 同步变更日志（TASK-004 幂等测试所需，event_log_id 唯一键）
-- ============================================================
CREATE TABLE IF NOT EXISTS sync_change_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    file_node_id  BIGINT       NOT NULL,
    change_type   VARCHAR(16)  NOT NULL,
    path          VARCHAR(1024) NOT NULL,
    old_path      VARCHAR(1024) DEFAULT NULL,
    name          VARCHAR(255) NOT NULL,
    node_type     TINYINT      NOT NULL,
    file_md5      VARCHAR(64)  DEFAULT NULL,
    file_size     BIGINT       DEFAULT 0,
    event_log_id  BIGINT       DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_event_log_id UNIQUE (event_log_id)
);


-- Schema 版本管理表（SV-001）
CREATE TABLE IF NOT EXISTS schema_version (
    id                BIGINT       NOT NULL,
    version_tag       VARCHAR(32)  NOT NULL,
    iteration_name    VARCHAR(255) NOT NULL,
    applied_sql_files CLOB,
    applied_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_by        VARCHAR(64)  DEFAULT NULL,
    notes             CLOB,
    PRIMARY KEY (id),
    CONSTRAINT uk_version_tag UNIQUE (version_tag)
);


-- 文件块布局表（块级增量同步，迭代 5）
CREATE TABLE IF NOT EXISTS file_block (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    file_node_id  BIGINT       NOT NULL,
    version       INT          NOT NULL,
    block_index   INT          NOT NULL,
    block_md5     VARCHAR(64)  NOT NULL,
    block_size    BIGINT       NOT NULL,
    storage_path  VARCHAR(512) NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);


-- 文件分享表（分享可选过期时间迭代：列定义对照 docker/mysql/init/02_create_tables.sql）
CREATE TABLE IF NOT EXISTS file_share (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    share_code      VARCHAR(32)  NOT NULL,
    file_node_id    BIGINT       NOT NULL,
    creator_id      BIGINT       NOT NULL,
    share_type      TINYINT      NOT NULL DEFAULT 0,
    password        VARCHAR(255) DEFAULT NULL,
    expire_at       DATETIME     DEFAULT NULL,
    permission      TINYINT      NOT NULL DEFAULT 0,
    allow_download  TINYINT      NOT NULL DEFAULT 1,
    permissions     VARCHAR(500) DEFAULT NULL,
    download_limit  INT          DEFAULT NULL,
    download_count  INT          NOT NULL DEFAULT 0,
    view_count      INT          NOT NULL DEFAULT 0,
    status          TINYINT      NOT NULL DEFAULT 1,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_share_code UNIQUE (share_code)
);

CREATE INDEX IF NOT EXISTS idx_share_creator ON file_share (creator_id, deleted);
CREATE INDEX IF NOT EXISTS idx_share_file_node ON file_share (file_node_id);

-- 全局配置表（系统级，不按租户隔离；对照 docker/mysql/init/38_share_security_config.sql）
CREATE TABLE IF NOT EXISTS sys_config (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL DEFAULT 0,
    config_key    VARCHAR(128) NOT NULL,
    config_value  VARCHAR(512) DEFAULT NULL,
    config_group  VARCHAR(64)  DEFAULT NULL,
    remark        VARCHAR(256) DEFAULT NULL,
    enabled       TINYINT      NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_config_key UNIQUE (config_key)
);

INSERT INTO sys_config (tenant_id, config_key, config_value, config_group, remark, enabled) VALUES
(0, 'share.brute_force.shareCodeLength', '12', 'share.brute_force.', 'share code length (8~16)', 1),
(0, 'share.brute_force.maxFailPerCode',  '5',  'share.brute_force.', 'per-code fail threshold', 1),
(0, 'share.brute_force.codeWindowMs',    '300000', 'share.brute_force.', 'per-code fail window (ms)', 1),
(0, 'share.brute_force.codeLockMs',      '900000', 'share.brute_force.', 'per-code lock (ms)', 1),
(0, 'share.brute_force.maxFailPerIp',    '20', 'share.brute_force.', 'per-ip fail threshold', 1),
(0, 'share.brute_force.ipWindowMs',      '600000', 'share.brute_force.', 'per-ip fail window (ms)', 1),
(0, 'share.brute_force.ipLockMs',        '1800000', 'share.brute_force.', 'per-ip lock (ms)', 1),
(0, 'share.brute_force.captchaEnabled',  'true', 'share.brute_force.', 'captcha enabled', 1),
(0, 'share.brute_force.captchaThreshold', '3', 'share.brute_force.', 'captcha trigger threshold', 1),
(0, 'share.brute_force.captchaLockMs',   '1800000', 'share.brute_force.', 'captcha fail lock (ms)', 1);
