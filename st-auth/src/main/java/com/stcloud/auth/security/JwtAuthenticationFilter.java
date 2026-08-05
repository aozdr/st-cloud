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

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtUtils jwtUtils;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (StringUtils.hasText(token) && jwtUtils.validateToken(token)) {
                Claims claims = jwtUtils.parseToken(token);

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
        } catch (Exception e) {
            log.warn("JWT认证失败: {}", e.getMessage());
        } finally {
            filterChain.doFilter(request, response);
            // 请求结束后清理上下文
            TenantContext.clear();
            UserContext.clear();
            SecurityContextHolder.clearContext();
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
