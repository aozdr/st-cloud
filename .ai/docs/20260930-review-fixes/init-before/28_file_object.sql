SET NAMES utf8mb4;
-- ============================================================
-- 文件对象表（去重/引用计数）  TASK-001
-- 同租户内按 md5 唯一：物理对象只存一份，file_node 通过 object_id 引用
-- 幂等加固（20260814-fix-m2m3）：重复执行不报错（information_schema 存在性守卫）
-- ============================================================
CREATE TABLE IF NOT EXISTS file_object (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '对象ID',
    tenant_id     BIGINT       NOT NULL                COMMENT '租户ID',
    md5           VARCHAR(64)  NOT NULL                COMMENT '文件MD5',
    size          BIGINT       NOT NULL DEFAULT 0      COMMENT '文件大小(字节)',
    storage_path  VARCHAR(500) NOT NULL                COMMENT '对象存储路径',
    ref_count     INT          NOT NULL DEFAULT 0      COMMENT '引用计数(同租户同md5的file_node数)',
    status        TINYINT      NOT NULL DEFAULT 0      COMMENT '状态：0-正常 1-已删除',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_md5 (tenant_id, md5),
    KEY idx_md5 (md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件对象表（同租户MD5去重）';

-- 幂等：仅当 object_id 列不存在时新增（可空：文件夹/未完成上传为 NULL）
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_node' AND COLUMN_NAME = 'object_id'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE file_node ADD COLUMN object_id BIGINT DEFAULT NULL COMMENT ''文件对象ID(去重引用)'' AFTER storage_path',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

-- 回填：为每个 (tenant_id, md5) 的已完成文件创建 file_object（canonical path 取组内最小，ref_count=组内节点数）
INSERT IGNORE INTO file_object (tenant_id, md5, size, storage_path, ref_count, status, deleted, created_at, updated_at)
SELECT n.tenant_id, n.file_md5, MIN(n.file_size), MIN(n.storage_path), COUNT(*), 0, 0, NOW(), NOW()
FROM file_node n
WHERE n.node_type = 1 AND n.upload_status = 2 AND n.deleted = 0
  AND n.file_md5 IS NOT NULL AND n.storage_path IS NOT NULL
GROUP BY n.tenant_id, n.file_md5;

-- 关联 file_node.object_id
UPDATE file_node n
JOIN file_object o ON n.tenant_id = o.tenant_id AND n.file_md5 = o.md5
SET n.object_id = o.id
WHERE n.node_type = 1 AND n.upload_status = 2 AND n.deleted = 0;
