-- 测试夹具：逐字复用 st-core H2 file_object DDL，无业务迁移。
CREATE TABLE IF NOT EXISTS file_object (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    md5           VARCHAR(64)  NOT NULL,
    size          BIGINT       NOT NULL DEFAULT 0,
    storage_path  VARCHAR(500) NOT NULL,
    ref_count     INT          NOT NULL DEFAULT 0,
    status        TINYINT      NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_md5 UNIQUE (tenant_id, md5)
);
