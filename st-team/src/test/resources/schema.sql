-- ============================================================
-- 测试用 H2 兼容建表脚本（st-team 集成测试）
-- 列对齐 docker/mysql/init/02_create_tables.sql + 17~25 号脚本
-- 去除 ENGINE/CHARSET/COLLATE/COMMENT（H2 不支持或不需要）
-- ============================================================

-- 用户表（st-auth：邀请成员/成员列表/角色昵称冗余需要）
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

-- 文件节点表（st-core：统计/锁定需要）
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

CREATE INDEX IF NOT EXISTS idx_fn_owner ON file_node (owner_id, deleted);

-- 团队空间表
CREATE TABLE IF NOT EXISTS team_space (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    space_name      VARCHAR(100)    NOT NULL,
    description     VARCHAR(500)    DEFAULT NULL,
    icon            VARCHAR(500)    DEFAULT NULL,
    owner_id        BIGINT          NOT NULL,
    storage_used    BIGINT          NOT NULL DEFAULT 0,
    storage_quota   BIGINT          DEFAULT NULL,
    status          TINYINT         NOT NULL DEFAULT 1,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

-- 团队成员表（含 22/25 号脚本新增列）
CREATE TABLE IF NOT EXISTS team_member (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    space_id        BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    role            TINYINT         NOT NULL DEFAULT 2,
    joined_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at  DATETIME        DEFAULT NULL,
    is_pinned       TINYINT         NOT NULL DEFAULT 0,
    member_type     TINYINT         NOT NULL DEFAULT 0,
    expire_at       DATETIME        DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_space_user UNIQUE (space_id, user_id)
);

-- 邀请链接表（17 号脚本）
CREATE TABLE IF NOT EXISTS team_invite (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    space_id        BIGINT          NOT NULL,
    invite_code     VARCHAR(32)     NOT NULL,
    role            TINYINT         NOT NULL DEFAULT 2,
    created_by      BIGINT          NOT NULL,
    expire_at       DATETIME        DEFAULT NULL,
    status          TINYINT         NOT NULL DEFAULT 1,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_invite_code UNIQUE (invite_code)
);

-- 活动日志表（18 号脚本，无逻辑删除）
CREATE TABLE IF NOT EXISTS team_activity (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    space_id        BIGINT          NOT NULL,
    user_id         BIGINT          DEFAULT NULL,
    username        VARCHAR(100)    DEFAULT NULL,
    nickname        VARCHAR(100)    DEFAULT NULL,
    action          VARCHAR(50)     NOT NULL,
    target_type     VARCHAR(30)     DEFAULT NULL,
    target_id       BIGINT          DEFAULT NULL,
    target_name     VARCHAR(255)    DEFAULT NULL,
    detail          CLOB            DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

-- 通知表（19 号脚本，无逻辑删除）
CREATE TABLE IF NOT EXISTS notification (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    type            VARCHAR(20)     NOT NULL,
    title           VARCHAR(200)    NOT NULL,
    content         VARCHAR(500)    DEFAULT NULL,
    ref_type        VARCHAR(20)     DEFAULT NULL,
    ref_id          BIGINT          DEFAULT NULL,
    `read`          TINYINT         NOT NULL DEFAULT 0,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

-- 评论表（20 号脚本）
CREATE TABLE IF NOT EXISTS team_comment (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    space_id        BIGINT          NOT NULL,
    node_id         BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    content         CLOB            NOT NULL,
    parent_id       BIGINT          DEFAULT NULL,
    mentions        VARCHAR(500)    DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

-- 文件夹权限表（21 号脚本）
CREATE TABLE IF NOT EXISTS team_folder_permission (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    space_id        BIGINT          NOT NULL,
    folder_node_id  BIGINT          NOT NULL,
    subject_type    VARCHAR(10)     NOT NULL,
    subject_id      BIGINT          NOT NULL,
    permission      TINYINT         NOT NULL,
    permissions     VARCHAR(500)    DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

-- 自定义角色表（24 号脚本）
CREATE TABLE IF NOT EXISTS team_role (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    space_id        BIGINT          NOT NULL,
    name            VARCHAR(50)     NOT NULL,
    permissions     VARCHAR(500)    NOT NULL,
    status          TINYINT         NOT NULL DEFAULT 1,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

-- 外部协作配置表（25 号脚本）
CREATE TABLE IF NOT EXISTS team_external_config (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    space_id        BIGINT          NOT NULL,
    allow_external  TINYINT         NOT NULL DEFAULT 0,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_space UNIQUE (space_id)
);
