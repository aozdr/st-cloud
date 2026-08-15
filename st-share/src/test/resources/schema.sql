-- ============================================================
-- st-share 集成测试用 H2 兼容建表脚本（file_share + file_node）
-- 列定义对照 docker/mysql/init/02_create_tables.sql 与 st-core schema.sql
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

CREATE TABLE IF NOT EXISTS file_share (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    share_code      VARCHAR(32)     NOT NULL,
    file_node_id    BIGINT          NOT NULL,
    creator_id      BIGINT          NOT NULL,
    share_type      TINYINT         NOT NULL DEFAULT 0,
    password        VARCHAR(255)    DEFAULT NULL,
    expire_at       DATETIME        DEFAULT NULL,
    permission      TINYINT         NOT NULL DEFAULT 0,
    allow_download  TINYINT         NOT NULL DEFAULT 1,
    permissions     VARCHAR(500)    DEFAULT NULL,
    download_limit  INT             DEFAULT NULL,
    download_count  INT             NOT NULL DEFAULT 0,
    view_count      INT             NOT NULL DEFAULT 0,
    status          TINYINT         NOT NULL DEFAULT 1,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_share_code UNIQUE (share_code)
);

CREATE INDEX IF NOT EXISTS idx_fn_owner ON file_node (owner_id, deleted);
CREATE INDEX IF NOT EXISTS idx_share_creator ON file_share (creator_id, deleted);
CREATE INDEX IF NOT EXISTS idx_share_file_node ON file_share (file_node_id);
