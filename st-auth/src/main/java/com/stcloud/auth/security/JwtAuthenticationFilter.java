package com.stcloud.auth.security;

import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String DOWNLOAD_USED_PREFIX = "stcloud:download:used:";
    /** 下载令牌仅允许用于单文件流式下载端点 /api/file/{nodeId}/stream */
    private static final Pattern STREAM_URI_PATTERN =
            Pattern.compile("^/api/file/(\\d+)/stream$");

    private final JwtUtils jwtUtils;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (StringUtils.hasText(token) && jwtUtils.validateToken(token)) {
                Claims claims = jwtUtils.parseToken(token);
                if ("download".equals(claims.get("type", String.class))
                        && !enforceDownloadToken(request, claims)) {
                    // 下载令牌未通过收敛策略：不建立认证上下文，交由 Spring Security 拒绝
                    log.warn("下载令牌未通过收敛策略，拒绝：uri={}", request.getRequestURI());
                } else {
                    Long userId = claims.get("userId", Long.class);
                    Long tenantId = claims.get("tenantId", Long.class);
                    String username = claims.getSubject();

                    // 解析角色和权限
                    List<String> roles = claims.get("roles", List.class);
                    List<String> permissions = claims.get("permissions", List.class);
                    if (roles == null) roles = List.of();
                    if (permissions == null) permissions = List.of();

                    // 解析数据范围（旧 token 缺失该 claim 时按角色回退，保证平滑过渡）
                    Integer dataScope = claims.get("dataScope", Integer.class);
                    if (dataScope == null) {
                        dataScope = roles.contains("admin") ? 3 : 1;
                    }

                    // 设置租户上下文
                    TenantContext.setTenantId(tenantId);

                    // 设置用户上下文
                    Set<String> permSet = new HashSet<>(permissions);
                    UserContext.setCurrentUser(UserContext.CurrentUser.builder()
                            .userId(userId)
                            .tenantId(tenantId)
                            .username(username)
                            .roles(roles)
                            .permissions(permSet)
                            .dataScope(dataScope)
                            .build());

                    // 设置 Spring Security 上下文
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                    // 每个权限码作为一个 GrantedAuthority，支持 @PreAuthorize("hasAuthority('xxx')")
                    for (String perm : permissions) {
                        authorities.add(new SimpleGrantedAuthority(perm));
                    }
                    for (String role : roles) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
                    }

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(username, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (Exception e) {
            log.warn("JWT 认证失败: {}", e.getMessage());
        } finally {
            filterChain.doFilter(request, response);
            // 请求结束后清理上下文
            TenantContext.clear();
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 下载令牌收敛策略：端点收敛 + nodeId 绑定 + 单次消费（容忍断点续传）。
     * 任一不满足则返回 false，调用方不建立认证上下文。
     */
    private boolean enforceDownloadToken(HttpServletRequest request, Claims claims) {
        // 1. 端点收敛：仅允许 /api/file/{nodeId}/stream
        String uri = request.getRequestURI();
        Matcher matcher = STREAM_URI_PATTERN.matcher(uri);
        if (!matcher.matches()) {
            log.warn("下载令牌被用于非下载端点，拒绝：uri={}", uri);
            return false;
        }
        // 2. nodeId 绑定：路径 nodeId 必须与令牌内 nodeId 一致
        Long pathNodeId = Long.valueOf(matcher.group(1));
        Long tokenNodeId = claims.get("nodeId", Long.class);
        if (tokenNodeId == null || !tokenNodeId.equals(pathNodeId)) {
            log.warn("下载令牌 nodeId 不匹配：token={}, path={}", tokenNodeId, pathNodeId);
            return false;
        }
        // 3. 单次消费：首次使用即登记 jti；已登记的仅放行断点续传（Range 起始字节 > 0）
        String jti = claims.getId();
        if (!StringUtils.hasText(jti)) {
            return false;
        }
        String key = DOWNLOAD_USED_PREFIX + jti;
        long ttlMs = claims.getExpiration().getTime() - System.currentTimeMillis();
        if (ttlMs <= 0) {
            return false;
        }
        Boolean firstUse = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", ttlMs, TimeUnit.MILLISECONDS);
        if (Boolean.TRUE.equals(firstUse)) {
            return true;
        }
        if (isResumeRange(request)) {
            return true;
        }
        log.warn("下载令牌重放被拒绝：jti={}, uri={}", jti, uri);
        return false;
    }

    /** 判断是否为断点续传请求：带 Range 头且起始字节 > 0 */
    private boolean isResumeRange(HttpServletRequest request) {
        String range = request.getHeader("Range");
        if (!StringUtils.hasText(range) || !range.startsWith("bytes=")) {
            return false;
        }
        String spec = range.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash <= 0) {
            return false;
        }
        try {
            return Long.parseLong(spec.substring(0, dash).trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTH_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        // 允许下载/流式接口通过 query 参数传递 token
        String paramToken = request.getParameter("token");
        if (StringUtils.hasText(paramToken) && isDownloadToken(paramToken)) {
            return paramToken;
        }
        if (StringUtils.hasText(paramToken)) {
            log.warn("拒绝在 URL query 中使用 access token，请改用下载令牌");
        }
        return null;
    }

    private boolean isDownloadToken(String token) {
        try {
            Claims claims = jwtUtils.parseToken(token);
            return "download".equals(claims.get("type", String.class));
        } catch (Exception e) {
            return false;
        }
    }
}