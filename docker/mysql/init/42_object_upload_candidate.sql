SET NAMES utf8mb4;

-- 新写入独立对象候选；不扫描/删除未登记的历史对象。
CREATE TABLE IF NOT EXISTS object_upload_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    md5 VARCHAR(64) NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    state VARCHAR(16) NOT NULL,
    write_finished TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_check_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_candidate_path (storage_path),
    KEY idx_candidate_scan (state, next_check_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- GC 逐路径引用检查，覆盖可恢复回收站和历史版本。
SET @upgrade_ddl = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='file_object' AND INDEX_NAME='idx_fo_gc_path'),
    'SELECT 1', 'CREATE INDEX idx_fo_gc_path ON file_object (tenant_id, storage_path)');
PREPARE environment_upgrade FROM @upgrade_ddl;
EXECUTE environment_upgrade;
DEALLOCATE PREPARE environment_upgrade;

SET @upgrade_ddl = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='file_node' AND INDEX_NAME='idx_fn_gc_path'),
    'SELECT 1', 'CREATE INDEX idx_fn_gc_path ON file_node (tenant_id, storage_path)');
PREPARE environment_upgrade FROM @upgrade_ddl;
EXECUTE environment_upgrade;
DEALLOCATE PREPARE environment_upgrade;

SET @upgrade_ddl = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='file_version' AND INDEX_NAME='idx_fv_gc_path'),
    'SELECT 1', 'CREATE INDEX idx_fv_gc_path ON file_version (tenant_id, storage_path)');
PREPARE environment_upgrade FROM @upgrade_ddl;
EXECUTE environment_upgrade;
DEALLOCATE PREPARE environment_upgrade;

SET @upgrade_ddl = IF(EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='upload_session' AND INDEX_NAME='idx_us_gc_md5'),
    'SELECT 1', 'CREATE INDEX idx_us_gc_md5 ON upload_session (tenant_id, file_md5, status)');
PREPARE environment_upgrade FROM @upgrade_ddl;
EXECUTE environment_upgrade;
DEALLOCATE PREPARE environment_upgrade;
