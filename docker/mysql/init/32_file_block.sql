SET NAMES utf8mb4;
-- ============================================================
-- 块级增量同步：文件块布局表（st-sync 模块，迭代 5）
-- ============================================================
-- 持久化每个文件版本的分块布局，供块级增量同步对比复用。
-- 块大小 5MB（与 S3 multipart 最小块约束一致），块存储路径 = 整文件对象路径（不单独存块对象）。
-- 块级同步通过 UploadPartCopy 从旧版本对象按字节范围复制未变块到新版本对象。

CREATE TABLE IF NOT EXISTS file_block (
    id            BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    tenant_id     BIGINT       NOT NULL                 COMMENT '租户ID',
    file_node_id  BIGINT       NOT NULL                 COMMENT '文件节点ID',
    version       INT          NOT NULL                 COMMENT '文件版本号（对齐 file_node.version）',
    block_index   INT          NOT NULL                 COMMENT '块序号（0-based）',
    block_md5     VARCHAR(64)  NOT NULL                 COMMENT '块MD5',
    block_size    BIGINT       NOT NULL                 COMMENT '块大小（字节）',
    storage_path  VARCHAR(512) NOT NULL                 COMMENT '块所属文件对象的S3存储路径',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_node_ver (file_node_id, version, block_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件块布局表（块级增量同步）';
