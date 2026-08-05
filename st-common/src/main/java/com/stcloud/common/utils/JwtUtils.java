package com.stcloud.common.utils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.entity.SysJwtSecret;
import com.stcloud.common.mapper.SysJwtSecretMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JWT 工具（Spring Bean）。
 *
 * <p>签名密钥不再硬编码于源码：首次启动随机生成 -> AES-GCM 加密后入库 ->
 * 运行时用环境变量主密钥（STCLOUD_MASTER_KEY）解密并缓存于内存。主密钥不落库、不入源码。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtils {

    private static final String CONFIG_KEY = "jwt-signing-key";
    private static final int SIGNING_KEY_BYTES = 32;
    private static final int AES_KEY_BYTES = 32;
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String AES = "AES";

    private final SysJwtSecretMapper jwtSecretMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${stcloud.jwt.master-key:}")
    private String masterKey;

    @Value("${stcloud.jwt.expiration:7200000}")
    private long expiration;

    @Value("${stcloud.jwt.refresh-expiration:2592000000}")
    private long refreshExpiration;

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        if (!StringUtils.hasText(masterKey)
                || masterKey.getBytes(StandardCharsets.UTF_8).length < AES_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT 主密钥未配置或长度不足 32 字节：请通过环境变量 STCLOUD_MASTER_KEY "
                            + "设置 stcloud.jwt.master-key（至少 32 字节）");
        }

        SecretKey aesKey = deriveAesKey(masterKey);
        SysJwtSecret record = loadRecord();
        if (record != null) {
            this.signingKey = toHmacKey(decrypt(aesKey, record.getSecretCiphertext(), record.getSecretIv()));
            log.info("JWT 签名密钥已从数据库加载");
            return;
        }

        byte[] keyBytes = randomBytes(SIGNING_KEY_BYTES);
        SysJwtSecret entity = buildEntity(aesKey, keyBytes);
        try {
            jwtSecretMapper.insert(entity);
            log.info("JWT 签名密钥已随机生成并加密入库");
        } catch (DuplicateKeyException e) {
            record = loadRecord();
            if (record == null) {
                throw new IllegalStateException("并发首次启动且重载仍无密钥记录", e);
            }
            keyBytes = decrypt(aesKey, record.getSecretCiphertext(), record.getSecretIv());
            log.warn("并发首次启动，已改用另一实例写入的密钥");
        }
        this.signingKey = toHmacKey(keyBytes);
    }

    public String generateToken(Long userId, Long tenantId, String username,
                                List<String> roles, List<String> permissions, int dataScope) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("tenantId", tenantId);
        claims.put("username", username);
        claims.put("roles", roles);
        claims.put("permissions", permissions);
        claims.put("dataScope", dataScope);
        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(signingKey)
                .compact();
    }

    public String generateRefreshToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("type", "refresh");
        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiration))
                .signWith(signingKey)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Long getUserId(String token) {
        return parseToken(token).get("userId", Long.class);
    }

    public Long getTenantId(String token) {
        Object tenantId = parseToken(token).get("tenantId");
        if (tenantId instanceof Integer) {
            return ((Integer) tenantId).longValue();
        }
        return (Long) tenantId;
    }

    public String getUsername(String token) {
        return parseToken(token).getSubject();
    }

    @SuppressWarnings("unchecked")
    public List<String> getRoles(String token) {
        List<String> roles = parseToken(token).get("roles", List.class);
        return roles != null ? roles : List.of();
    }

    @SuppressWarnings("unchecked")
    public List<String> getPermissions(String token) {
        List<String> permissions = parseToken(token).get("permissions", List.class);
        return permissions != null ? permissions : List.of();
    }

    // ==================== 密钥加密存储 ====================

    private SysJwtSecret loadRecord() {
        return jwtSecretMapper.selectOne(
                new LambdaQueryWrapper<SysJwtSecret>()
                        .eq(SysJwtSecret::getConfigKey, CONFIG_KEY)
                        .last("LIMIT 1"));
    }

    private SysJwtSecret buildEntity(SecretKey aesKey, byte[] keyBytes) {
        byte[] iv = randomBytes(IV_BYTES);
        byte[] cipherText = encrypt(aesKey, keyBytes, iv);
        SysJwtSecret entity = new SysJwtSecret();
        entity.setConfigKey(CONFIG_KEY);
        entity.setSecretCiphertext(Base64.getEncoder().encodeToString(cipherText));
        entity.setSecretIv(Base64.getEncoder().encodeToString(iv));
        return entity;
    }

    private SecretKey toHmacKey(byte[] keyBytes) {
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private SecretKey deriveAesKey(String master) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(master.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, AES);
        } catch (Exception e) {
            throw new IllegalStateException("派生 AES 主密钥失败", e);
        }
    }

    private byte[] encrypt(SecretKey aesKey, byte[] plain, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(plain);
        } catch (Exception e) {
            throw new IllegalStateException("加密 JWT 签名密钥失败", e);
        }
    }

    private byte[] decrypt(SecretKey aesKey, String cipherTextB64, String ivB64) {
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM);
            byte[] iv = Base64.getDecoder().decode(ivB64);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(Base64.getDecoder().decode(cipherTextB64));
        } catch (Exception e) {
            throw new IllegalStateException("解密 JWT 签名密钥失败：主密钥可能已变更或不匹配", e);
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return bytes;
    }
}