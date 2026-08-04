package com.stcloud.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JwtUtils {

    private static final String SECRET = "stcloud-secret-key-must-be-at-least-32-chars-long";
    private static final long EXPIRATION = 7 * 24 * 60 * 60 * 1000L; // 7天
    private static final long REFRESH_EXPIRATION = 30 * 24 * 60 * 60 * 1000L; // 30天

    private static SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    public static String generateToken(Long userId, Long tenantId, String username,
                                        boolean admin, List<String> roles, List<String> permissions) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("tenantId", tenantId);
        claims.put("username", username);
        claims.put("admin", admin);
        claims.put("roles", roles);
        claims.put("permissions", permissions);
        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION))
                .signWith(getSigningKey())
                .compact();
    }

    public static String generateRefreshToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("type", "refresh");
        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + REFRESH_EXPIRATION))
                .signWith(getSigningKey())
                .compact();
    }

    public static Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static Long getUserId(String token) {
        Claims claims = parseToken(token);
        return claims.get("userId", Long.class);
    }

    public static Long getTenantId(String token) {
        Claims claims = parseToken(token);
        Object tenantId = claims.get("tenantId");
        if (tenantId instanceof Integer) {
            return ((Integer) tenantId).longValue();
        }
        return (Long) tenantId;
    }

    public static String getUsername(String token) {
        return parseToken(token).getSubject();
    }

    public static boolean isAdmin(String token) {
        Claims claims = parseToken(token);
        return claims.get("admin", Boolean.class);
    }

    @SuppressWarnings("unchecked")
    public static List<String> getRoles(String token) {
        Claims claims = parseToken(token);
        List<String> roles = claims.get("roles", List.class);
        return roles != null ? roles : List.of();
    }

    @SuppressWarnings("unchecked")
    public static List<String> getPermissions(String token) {
        Claims claims = parseToken(token);
        List<String> permissions = claims.get("permissions", List.class);
        return permissions != null ? permissions : List.of();
    }
}
