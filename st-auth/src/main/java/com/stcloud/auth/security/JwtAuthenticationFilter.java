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
                String tokenType = claims.get("type", String.class);
                boolean streamToken = "download".equals(tokenType) || "editor".equals(tokenType);
                if (streamToken && !enforceStreamToken(request, claims, tokenType)) {
                    // 流式令牌（download/editor）未通过收敛策略：不建立认证上下文，交由 Spring Security 拒绝
                    log.warn("{} 令牌未通过收敛策略，拒绝：uri={}", tokenType, request.getRequestURI());
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
     * 流式令牌收敛策略（download/editor）：端点收敛 + nodeId 绑定。
     * download 额外单次消费（容忍断点续传）；editor 不单次消费（OnlyOffice 保存期间多次拉取文档）。
     * 任一不满足则返回 false，调用方不建立认证上下文。
     */
    private boolean enforceStreamToken(HttpServletRequest request, Claims claims, String tokenType) {
        // 1. 端点收敛：仅允许 /api/file/{nodeId}/stream
        String uri = request.getRequestURI();
        Matcher matcher = STREAM_URI_PATTERN.matcher(uri);
        if (!matcher.matches()) {
            log.warn("{} 令牌被用于非流式端点，拒绝：uri={}", tokenType, uri);
            return false;
        }
        // 2. nodeId 绑定：路径 nodeId 必须与令牌内 nodeId 一致
        Long pathNodeId = Long.valueOf(matcher.group(1));
        Long tokenNodeId = claims.get("nodeId", Long.class);
        if (tokenNodeId == null || !tokenNodeId.equals(pathNodeId)) {
            log.warn("{} 令牌 nodeId 不匹配：token={}, path={}", tokenType, tokenNodeId, pathNodeId);
            return false;
        }
        if ("download".equals(tokenType)) {
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
        // editor 令牌：不单次消费（OnlyOffice 需要多次下载文档内容，TC-21）
        return true;
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
            String headerToken = bearerToken.substring(BEARER_PREFIX.length());
            // header token 有效则优先使用；无效时回退 query 下载令牌。
            // 实测 OnlyOffice 下载 document.url 时会携带非下载令牌的 Authorization 头，
            // 若直接采用 header 会导致流式下载 401（忽略 query 中有效的 editor token）。
            if (jwtUtils.validateToken(headerToken)) {
                return headerToken;
            }
            // header token 无效：继续尝试 query 中的下载/编辑器流式令牌
        }
        // 允许下载/编辑器流式接口通过 query 参数传递 token（download / editor 类型）
        String paramToken = request.getParameter("token");
        if (StringUtils.hasText(paramToken) && isStreamToken(paramToken)) {
            return paramToken;
        }
        if (StringUtils.hasText(paramToken)) {
            log.warn("拒绝在 URL query 中使用 access token，请改用下载令牌");
        }
        return null;
    }

    private boolean isStreamToken(String token) {
        try {
            Claims claims = jwtUtils.parseToken(token);
            String type = claims.get("type", String.class);
            return "download".equals(type) || "editor".equals(type);
        } catch (Exception e) {
            return false;
        }
    }
}
