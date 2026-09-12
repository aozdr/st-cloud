SET NAMES utf8mb4;

-- P0-04：服务端权威上传会话，禁止仅凭 upload_id/s3_upload_id 操作他人上传。
CREATE TABLE IF NOT EXISTS upload_session (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    tenant_id       BIGINT          NOT NULL COMMENT '租户ID',
    upload_id       VARCHAR(200)    NOT NULL COMMENT '服务端生成的不可预测上传ID',
    user_id         BIGINT          NOT NULL COMMENT '会话所属用户',
    file_node_id    BIGINT          NOT NULL COMMENT '关联文件节点',
    space_id        BIGINT          DEFAULT NULL COMMENT '团队空间ID，NULL=个人上传',
    storage_path    VARCHAR(500)    NOT NULL COMMENT '服务端对象存储路径',
    s3_upload_id    VARCHAR(500)    NOT NULL COMMENT '服务端对象存储分片会话ID',
    file_size       BIGINT          NOT NULL COMMENT '文件大小(字节)',
    file_md5        VARCHAR(64)     NOT NULL COMMENT '文件MD5',
    total_chunks    INT             NOT NULL COMMENT '总分片数',
    chunk_size      BIGINT          NOT NULL COMMENT '分片大小(字节)',
    client_limit    INT             DEFAULT NULL COMMENT '服务端记录的客户端限速上限(KB/s)',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0-active 1-merging 2-completed 3-aborted 4-failed 5-expired',
    expires_at      DATETIME        NOT NULL COMMENT '会话过期时间',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_upload_session_upload_id (upload_id),
    KEY idx_upload_session_owner (tenant_id, user_id, status),
    KEY idx_upload_session_node (file_node_id),
    KEY idx_upload_session_expire (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分片上传会话';
