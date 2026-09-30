SET NAMES utf8mb4;
-- ============================================================
-- 事件 Outbox 表（事务内落库，事务提交后投递 RocketMQ / 本地兜底）  TASK-004
-- 回滚即不产生事件；投递失败标 failed 由定时任务重投；消费者按 event_log_id 幂等
-- ============================================================
CREATE TABLE IF NOT EXISTS event_log (
    id            BIGINT       NOT NULL COMMENT '事件日志ID（雪花，兼作消费者幂等键）',
    tenant_id     BIGINT       DEFAULT NULL                COMMENT '租户ID',
    event_type    VARCHAR(32)  NOT NULL                    COMMENT '事件类型：FILE_INDEX / SYNC_CHANGE',
    payload       TEXT         NOT NULL                    COMMENT '事件负载 JSON（EventMessage）',
    status        TINYINT      NOT NULL DEFAULT 0          COMMENT '状态：0-待投递 1-已投递 2-投递失败',
    retry_count   INT          NOT NULL DEFAULT 0          COMMENT '重试次数',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    processed_at  DATETIME     DEFAULT NULL                COMMENT '成功投递时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0          COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_status (status, retry_count, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件 Outbox 表（事务性可靠事件）';