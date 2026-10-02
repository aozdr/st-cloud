SET NAMES utf8mb4;

-- 旧环境或中断迁移重试：仅补缺少的 GC 索引，不重建已有索引或修改业务数据。
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


