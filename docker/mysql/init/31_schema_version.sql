SET NAMES utf8mb4;
-- ============================================================
-- Schema 版本管理表  -- 每次迭代更新版本号，记录本次执行的 SQL 文件清单
-- ============================================================
CREATE TABLE IF NOT EXISTS schema_version (
    id                BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键ID',
    version_tag       VARCHAR(32)  NOT NULL                 COMMENT '版本号，格式 YYYYMMDD.N（N=当日序号）',
    iteration_name    VARCHAR(255) NOT NULL                 COMMENT '迭代名称/主题',
    applied_sql_files TEXT                                  COMMENT '本次执行的 SQL 文件清单，逗号分隔（相对 docker/mysql/init/ 路径）',
    applied_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
    applied_by        VARCHAR(64)  DEFAULT NULL             COMMENT '执行人/Agent 标识',
    notes             TEXT                                  COMMENT '备注（变更摘要、风险等）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_tag (version_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库 Schema 版本记录表';

-- 初始基线版本：记录历史全量迁移（02~25 为建库以来累计执行）
INSERT INTO schema_version (version_tag, iteration_name, applied_sql_files, applied_by, notes)
VALUES ('20260811.1', '基线版本（历史全量迁移）',
        '02_create_tables.sql,04_rbac_tables.sql,05_rate_limit_tables.sql,06_sync_tables.sql,07_cloud_capacity.sql,08_chunk_original_size.sql,09_jwt_secret.sql,09_remove_two_factor.sql,10_drop_is_admin.sql,11_role_data_scope.sql,12_add_permissions.sql,13_remove_ratelimit_orphan.sql,14_add_preview_permission.sql,15_add_file_favorite.sql,16_add_file_hidden.sql,17_team_invite.sql,18_team_activity.sql,19_notification.sql,20_team_comment.sql,21_team_folder_permission.sql,22_team_member_pinned.sql,23_file_lock.sql,24_team_role.sql,25_team_external.sql',
        'codex-agent',
        '基线：02~25 号迁移已在历史迭代中执行到 MySQL');

-- 当前迭代版本：审查遗留建议 + Schema 一致性修复（补执行遗漏的 26~30）
INSERT INTO schema_version (version_tag, iteration_name, applied_sql_files, applied_by, notes)
VALUES ('20260812.1', '审查遗留建议跟进 + Schema 一致性修复',
        '26_sync_change_log.sql,27_sync_exclusion_conflict.sql,28_file_object.sql,29_event_log.sql,30_sync_change_log_event_log_id.sql,31_schema_version.sql',
        'codex-agent',
        '补执行遗漏的迁移 26~30（sync_change_log/file_object/event_log/唯一索引）；修复 27 号唯一键超 3072 字节；新增 schema_version 版本表');

-- 2026-08-15：MySQL 容器与数据卷重建，修复 init 脚本中文双重编码（02~36 全部脚本首行加 SET NAMES utf8mb4）
INSERT INTO schema_version (version_tag, iteration_name, applied_sql_files, applied_by, notes)
VALUES ('20260815.1', 'MySQL 重建：修复 init 脚本中文双重编码',
        '02~36 全部 init 脚本（首行统一加 SET NAMES utf8mb4），docker_mysql_data 数据卷重建',
        'codex',
        '容器内 mysql 客户端默认 latin1 导致 init 中文被双重编码为乱码；业务数据已恢复（临时备份已清理）');
