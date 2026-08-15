-- ============================================================
-- sync_change_log 增加 event_log_id（MQ 消费者幂等去重键）  TASK-004
-- 幂等加固（20260813-code-review-fix）：重复执行不报错（information_schema 存在性守卫）
-- ============================================================

-- 幂等：仅当 event_log_id 列不存在时新增
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_change_log' AND COLUMN_NAME = 'event_log_id'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE sync_change_log ADD COLUMN event_log_id BIGINT DEFAULT NULL COMMENT ''事件Outbox日志ID（MQ 重复投递幂等键，本地兜底时为 NULL）'' AFTER file_size',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

-- 幂等：仅当唯一键不存在时新增
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_change_log' AND INDEX_NAME = 'uk_event_log_id'
);
SET @sql2 := IF(@idx_exists = 0,
    'ALTER TABLE sync_change_log ADD UNIQUE KEY uk_event_log_id (event_log_id)',
    'SELECT 1');
PREPARE stmt_idx FROM @sql2;
EXECUTE stmt_idx;
DEALLOCATE PREPARE stmt_idx;
