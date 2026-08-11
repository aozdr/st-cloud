-- ============================================================
-- 文件锁定字段（st-core 模块）
-- ============================================================
ALTER TABLE file_node ADD COLUMN locked_by BIGINT DEFAULT NULL COMMENT '锁定人ID，NULL=未锁定';
ALTER TABLE file_node ADD COLUMN locked_at DATETIME DEFAULT NULL COMMENT '锁定时间';
ALTER TABLE file_node ADD COLUMN lock_expire_at DATETIME DEFAULT NULL COMMENT '锁定过期时间，NULL=永久';