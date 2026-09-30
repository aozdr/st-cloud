SET NAMES utf8mb4;

-- 规范对象写入失败后的延迟回收候选；跨实例安全边界由状态和删除前复核提供。
CREATE TABLE IF NOT EXISTS file_orphan_candidate (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '候选ID',
    tenant_id       BIGINT       NOT NULL COMMENT '租户ID',
    md5             VARCHAR(64)  NOT NULL COMMENT '文件MD5',
    storage_path    VARCHAR(500) NOT NULL COMMENT '规范对象路径',
    active_uploads  INT          NOT NULL DEFAULT 0 COMMENT '仍可能写入/提交该对象的请求数',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0-active 1-pending 2-deleting',
    candidate_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '进入候选时间',
    last_active_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近活动时间',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orphan_tenant_path (tenant_id, storage_path),
    KEY idx_orphan_due (status, active_uploads, candidate_at),
    KEY idx_orphan_tenant_md5 (tenant_id, md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='规范对象孤儿回收候选';

INSERT INTO schema_version (version_tag, iteration_name, applied_sql_files, applied_by, notes)
VALUES ('20260914.1', '第三轮代码复核：规范对象孤儿延迟回收',
        '42_file_orphan_candidate.sql', 'codex',
        '规范对象失败清理改为宽限期候选；删除前复核 NORMAL file_object、有效 file_node 引用和活动上传会话')
ON DUPLICATE KEY UPDATE version_tag = VALUES(version_tag);
