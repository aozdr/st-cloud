SET NAMES utf8mb4;

-- 已执行 45 的库无需再改结构；漏执行或重试时补齐列，保留所有已有安全版本。
SET @security_version_ddl = IF(
    EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'security_version'),
    'SELECT 1',
    'ALTER TABLE sys_user ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0 COMMENT ''会话安全版本'''
);
PREPARE security_version_migration FROM @security_version_ddl;
EXECUTE security_version_migration;
DEALLOCATE PREPARE security_version_migration;
