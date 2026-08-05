-- 星云盘 数据库初始化脚本
-- 创建数据库
CREATE DATABASE IF NOT EXISTS stcloud DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE stcloud;

-- ============================================================
-- 1. 租户表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_tenant (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '租户ID',
    tenant_name     VARCHAR(100)    NOT NULL                 COMMENT '租户名称',
    tenant_code     VARCHAR(50)     NOT NULL                 COMMENT '租户编码',
    domain          VARCHAR(255)    DEFAULT NULL             COMMENT '绑定域名',
    status          TINYINT         NOT NULL DEFAULT 1       COMMENT '状态：0-禁用 1-正常',
    default_quota   BIGINT          NOT NULL DEFAULT 10737418240 COMMENT '默认配额 10GB',
    cloud_total_capacity BIGINT      DEFAULT NULL             COMMENT '云盘总容量(字节)，NULL=不限',
    expire_at       DATETIME        DEFAULT NULL             COMMENT '过期时间',
    tenant_id       BIGINT          DEFAULT NULL             COMMENT '租户ID（BaseEntity继承字段）',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (tenant_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户表';

-- ============================================================
-- 2. 用户表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '用户ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    username        VARCHAR(50)     NOT NULL                 COMMENT '用户名',
    password        VARCHAR(255)    NOT NULL                 COMMENT '密码(BCrypt)',
    nickname        VARCHAR(100)    DEFAULT NULL             COMMENT '昵称',
    email           VARCHAR(100)    DEFAULT NULL             COMMENT '邮箱',
    phone           VARCHAR(20)     DEFAULT NULL             COMMENT '手机号',
    avatar          VARCHAR(500)    DEFAULT NULL             COMMENT '头像URL',
    status          TINYINT         NOT NULL DEFAULT 1       COMMENT '状态：0-禁用 1-正常',
    storage_used    BIGINT          NOT NULL DEFAULT 0       COMMENT '已用存储(字节)',
    storage_quota   BIGINT          DEFAULT NULL             COMMENT '存储配额(字节)，NULL=使用租户默认',
    last_login_at   DATETIME        DEFAULT NULL             COMMENT '最后登录时间',
    last_login_ip   VARCHAR(50)     DEFAULT NULL             COMMENT '最后登录IP',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_username (tenant_id, username),
    KEY idx_email (email),
    KEY idx_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ============================================================
-- 3. 文件节点表 (核心表)
-- ============================================================
CREATE TABLE IF NOT EXISTS file_node (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '节点ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    parent_id       BIGINT          NOT NULL DEFAULT 0       COMMENT '父文件夹ID，0=根目录',
    node_type       TINYINT         NOT NULL                 COMMENT '节点类型：0-文件夹 1-文件',
    name            VARCHAR(255)    NOT NULL                 COMMENT '文件/文件夹名',
    path            VARCHAR(1000)   NOT NULL DEFAULT '/'      COMMENT '完整路径',
    file_size       BIGINT          NOT NULL DEFAULT 0       COMMENT '文件大小(字节)',
    file_md5        VARCHAR(64)     DEFAULT NULL             COMMENT '文件MD5(秒传/去重)',
    content_type    VARCHAR(100)    DEFAULT NULL             COMMENT 'MIME类型',
    suffix          VARCHAR(20)     DEFAULT NULL             COMMENT '文件后缀',
    storage_path    VARCHAR(500)    DEFAULT NULL             COMMENT '对象存储路径',
    status          TINYINT         NOT NULL DEFAULT 0       COMMENT '状态：0-正常 1-回收站 2-已删除',
    upload_status   TINYINT         NOT NULL DEFAULT 0       COMMENT '上传状态：0-待上传 1-上传中 2-已完成 3-失败',
    uploader_id     BIGINT          NOT NULL                 COMMENT '上传者ID',
    owner_id        BIGINT          NOT NULL                 COMMENT '所有者ID',
    space_id        BIGINT          DEFAULT NULL             COMMENT '团队空间ID，NULL=个人文件',
    ref_count       INT             NOT NULL DEFAULT 1       COMMENT '存储引用计数(去重)',
    version         INT             NOT NULL DEFAULT 1       COMMENT '当前版本号',
    thumbnail_path  VARCHAR(500)    DEFAULT NULL             COMMENT '缩略图路径',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_tenant_parent (tenant_id, parent_id, deleted),
    KEY idx_tenant_owner (tenant_id, owner_id, deleted),
    KEY idx_md5 (file_md5),
    KEY idx_space (space_id, deleted),
    KEY idx_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件节点表';

-- ============================================================
-- 4. 文件分片表
-- ============================================================
CREATE TABLE IF NOT EXISTS file_chunk (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '分片ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    upload_id       VARCHAR(200)    NOT NULL                 COMMENT '上传唯一标识(MD5+时间戳)',
    file_node_id    BIGINT          NOT NULL                 COMMENT '关联文件节点ID',
    chunk_index     INT             NOT NULL                 COMMENT '分片序号(从0开始)',
    chunk_size      BIGINT          NOT NULL                 COMMENT '分片大小(字节)',
    chunk_md5       VARCHAR(64)     DEFAULT NULL             COMMENT '分片MD5',
    storage_path    VARCHAR(500)    DEFAULT NULL             COMMENT '分片存储路径',
    original_size   BIGINT          DEFAULT NULL             COMMENT '替换上传时原文件大小(字节)，用于合并时按差值计费',
    status          TINYINT         NOT NULL DEFAULT 0       COMMENT '状态：0-待上传 1-已上传 2-已合并',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_upload_chunk (upload_id, chunk_index),
    KEY idx_file_node (file_node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件分片表';

-- ============================================================
-- 5. 文件版本表
-- ============================================================
CREATE TABLE IF NOT EXISTS file_version (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '版本ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    file_node_id    BIGINT          NOT NULL                 COMMENT '文件节点ID',
    version_num     INT             NOT NULL                 COMMENT '版本号',
    file_size       BIGINT          NOT NULL                 COMMENT '文件大小',
    file_md5        VARCHAR(64)     NOT NULL                 COMMENT '文件MD5',
    storage_path    VARCHAR(500)    NOT NULL                 COMMENT '存储路径',
    modifier_id     BIGINT          NOT NULL                 COMMENT '修改人ID',
    modifier_name   VARCHAR(100)    DEFAULT NULL             COMMENT '修改人名称',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_file_node (file_node_id),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件版本表';

-- ============================================================
-- 6. 文件分享表
-- ============================================================
CREATE TABLE IF NOT EXISTS file_share (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '分享ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    share_code      VARCHAR(32)     NOT NULL                 COMMENT '分享码(短链)',
    file_node_id    BIGINT          NOT NULL                 COMMENT '分享的文件节点ID',
    creator_id      BIGINT          NOT NULL                 COMMENT '创建者ID',
    share_type      TINYINT         NOT NULL DEFAULT 0       COMMENT '分享类型：0-公开 1-私密(提取码)',
    password        VARCHAR(255)    DEFAULT NULL             COMMENT '访问密码(BCrypt)',
    expire_at       DATETIME        DEFAULT NULL             COMMENT '过期时间，NULL=永久',
    permission      TINYINT         NOT NULL DEFAULT 0       COMMENT '权限：0-查看 1-下载 2-上传 3-编辑',
    download_limit  INT             DEFAULT NULL             COMMENT '下载次数限制，NULL=不限',
    download_count  INT             NOT NULL DEFAULT 0       COMMENT '已下载次数',
    view_count      INT             NOT NULL DEFAULT 0       COMMENT '访问次数',
    status          TINYINT         NOT NULL DEFAULT 1       COMMENT '状态：0-已取消 1-有效',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_share_code (share_code),
    KEY idx_creator (creator_id, deleted),
    KEY idx_file_node (file_node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件分享表';

-- ============================================================
-- 7. 团队空间表
-- ============================================================
CREATE TABLE IF NOT EXISTS team_space (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '空间ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    space_name      VARCHAR(100)    NOT NULL                 COMMENT '空间名称',
    description     VARCHAR(500)    DEFAULT NULL             COMMENT '空间描述',
    icon            VARCHAR(500)    DEFAULT NULL             COMMENT '空间图标',
    owner_id        BIGINT          NOT NULL                 COMMENT '创建者ID',
    storage_used    BIGINT          NOT NULL DEFAULT 0       COMMENT '已用存储',
    storage_quota   BIGINT          DEFAULT NULL             COMMENT '存储配额，NULL=不限',
    status          TINYINT         NOT NULL DEFAULT 1       COMMENT '状态：0-禁用 1-正常',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_tenant_owner (tenant_id, owner_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队空间表';

-- ============================================================
-- 8. 团队成员表
-- ============================================================
CREATE TABLE IF NOT EXISTS team_member (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '成员ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    space_id        BIGINT          NOT NULL                 COMMENT '团队空间ID',
    user_id         BIGINT          NOT NULL                 COMMENT '用户ID',
    role            TINYINT         NOT NULL DEFAULT 2       COMMENT '角色：0-管理员 1-编辑者 2-查看者',
    joined_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    last_active_at  DATETIME        DEFAULT NULL             COMMENT '最后活跃时间',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0       COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_space_user (space_id, user_id),
    KEY idx_user (user_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队成员表';

-- ============================================================
-- 9. 同步设备表
-- ============================================================
CREATE TABLE IF NOT EXISTS sync_device (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '设备ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    user_id         BIGINT          NOT NULL                 COMMENT '用户ID',
    device_name     VARCHAR(100)    NOT NULL                 COMMENT '设备名称',
    device_type     VARCHAR(20)     NOT NULL                 COMMENT '设备类型：Windows/macOS',
    device_id       VARCHAR(100)    NOT NULL                 COMMENT '设备唯一标识',
    sync_path       VARCHAR(500)    DEFAULT NULL             COMMENT '同步路径',
    last_sync_at    DATETIME        DEFAULT NULL             COMMENT '最后同步时间',
    status          TINYINT         NOT NULL DEFAULT 1       COMMENT '状态：0-已停用 1-正常',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_device (user_id, device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='同步设备表';

-- ============================================================
-- 10. 审计日志表
-- ============================================================
CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '日志ID',
    tenant_id       BIGINT          NOT NULL                 COMMENT '租户ID',
    user_id         BIGINT          DEFAULT NULL             COMMENT '操作用户ID',
    username        VARCHAR(100)    DEFAULT NULL             COMMENT '用户名',
    action          VARCHAR(50)     NOT NULL                 COMMENT '操作类型：UPLOAD/DOWNLOAD/DELETE/SHARE等',
    target_type     VARCHAR(30)     DEFAULT NULL             COMMENT '目标类型：FILE/FOLDER/SHARE/TEAM等',
    target_id       BIGINT          DEFAULT NULL             COMMENT '目标ID',
    target_name     VARCHAR(255)    DEFAULT NULL             COMMENT '目标名称',
    detail          TEXT            DEFAULT NULL             COMMENT '操作详情(JSON)',
    ip_address      VARCHAR(50)     DEFAULT NULL             COMMENT 'IP地址',
    user_agent      VARCHAR(500)    DEFAULT NULL             COMMENT 'User-Agent',
    status          TINYINT         NOT NULL DEFAULT 1       COMMENT '操作结果：0-失败 1-成功',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_tenant_user (tenant_id, user_id),
    KEY idx_action (action),
    KEY idx_created (created_at),
    KEY idx_ip (ip_address),
    KEY idx_target_name (target_name),
    KEY idx_user_action (username, action),
    KEY idx_created_action (created_at, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表';

-- ============================================================
-- 11. 初始数据
-- ============================================================

-- 默认租户
INSERT INTO sys_tenant (id, tenant_name, tenant_code, status, default_quota)
VALUES (1, '默认租户', 'default', 1, 10737418240)
ON DUPLICATE KEY UPDATE tenant_name='默认租户';

-- 默认管理员 (密码: admin123, BCrypt加密)
INSERT INTO sys_user (id, tenant_id, username, password, nickname, storage_quota)
VALUES (1, 1, 'admin', '$2b$10$mLMRBZxVo/uyk0EvmfvC9eCUPOFCcs4EA38LYI4gmq8oHfJe6ALWW', '管理员', 10737418240)
ON DUPLICATE KEY UPDATE nickname='管理员';
