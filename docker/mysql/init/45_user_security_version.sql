SET NAMES utf8mb4;

-- 安全操作在同一事务内递增版本；旧会话随后在主库校验中失效。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS；元数据保护允许迁移重试且不重置已有版本。
SET @security_version_ddl = IF(
    EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'security_version'),
    'SELECT 1',
    'ALTER TABLE sys_user ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0 COMMENT ''会话安全版本'''
);
PREPARE security_version_migration FROM @security_version_ddl;
EXECUTE security_version_migration;
DEALLOCATE PREPARE security_version_migration;
