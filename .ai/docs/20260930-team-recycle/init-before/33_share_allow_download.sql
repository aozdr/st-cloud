SET NAMES utf8mb4;
-- ============================================================
-- file_share 增加 allow_download（下载/流式统一开关）  TASK-FIX-SEC-DOWNLOAD-FLAG
-- S-02 加固：permission 保留用于前端展示/兼容，下载控制以 allow_download 为权威
-- 幂等：information_schema 存在性守卫，重复执行不报错（对齐 30 号脚本模式）
-- ============================================================

-- 幂等：仅当 allow_download 列不存在时新增
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_share' AND COLUMN_NAME = 'allow_download'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE file_share ADD COLUMN allow_download TINYINT NOT NULL DEFAULT 1 COMMENT ''允许下载：0-禁止 1-允许（下载URL与流式统一开关）'' AFTER permission',
    'SELECT 1');
PREPARE stmt_col FROM @sql;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

-- 历史数据联动：仅查看（permission=0）的旧分享迁移后默认禁止下载，避免语义反转
UPDATE file_share SET allow_download = 0 WHERE permission = 0;
