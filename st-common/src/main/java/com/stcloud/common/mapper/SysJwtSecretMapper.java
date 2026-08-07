package com.stcloud.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.common.entity.SysJwtSecret;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysJwtSecretMapper extends BaseMapper<SysJwtSecret> {

    /**
     * 幂等建表：未执行 docker/mysql/init/09_jwt_secret.sql 的库首次启动时自愈，避免缺表导致启动失败。
     * 生产环境表已存在，此语句为 no-op；若账号无 DDL 权限则由调用方忽略异常。
     */
    @Update("CREATE TABLE IF NOT EXISTS sys_jwt_secret (id BIGINT NOT NULL COMMENT '主键', config_key VARCHAR(64) NOT NULL COMMENT '配置键，固定 jwt-signing-key', secret_ciphertext VARCHAR(512) NOT NULL COMMENT 'Base64 密文（含 GCM 认证标签）', secret_iv VARCHAR(64) NOT NULL COMMENT 'Base64 初始向量', tenant_id BIGINT NULL COMMENT '系统级配置预留，恒为空', created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间', updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间', deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除', PRIMARY KEY (id), UNIQUE KEY uk_config_key (config_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='JWT 签名密钥（加密存储）'")
    void createTableIfNotExists();
}