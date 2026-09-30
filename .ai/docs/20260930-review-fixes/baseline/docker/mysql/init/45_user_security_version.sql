SET NAMES utf8mb4;

-- 安全操作在同一事务内递增版本；旧会话随后在主库校验中失效。
ALTER TABLE sys_user
    ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0 COMMENT '会话安全版本';
