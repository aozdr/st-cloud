-- ============================================================
-- 云盘总容量：个人与团队共享的物理存储上限
-- ============================================================
USE stcloud;

ALTER TABLE sys_tenant ADD COLUMN cloud_total_capacity BIGINT DEFAULT NULL COMMENT '云盘总容量(字节)，NULL=不限' AFTER default_quota;

-- 默认总容量 100GB
UPDATE sys_tenant SET cloud_total_capacity = 107374182400 WHERE cloud_total_capacity IS NULL;
