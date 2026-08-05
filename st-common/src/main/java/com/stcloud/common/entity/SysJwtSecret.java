package com.stcloud.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * JWT 签名密钥实体 - 密文存储，运行时由主密钥解密
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_jwt_secret")
public class SysJwtSecret extends BaseEntity {

    /** 配置键，固定 jwt-signing-key */
    private String configKey;

    /** Base64 密文（含 GCM 认证标签） */
    private String secretCiphertext;

    /** Base64 初始向量 */
    private String secretIv;
}