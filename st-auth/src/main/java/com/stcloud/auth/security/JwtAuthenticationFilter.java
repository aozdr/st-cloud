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

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (StringUtils.hasText(token) && JwtUtils.validateToken(token)) {
                Claims claims = JwtUtils.parseToken(token);

                Long userId = claims.get("userId", Long.class);
                Long tenantId = claims.get("tenantId", Long.class);
                String username = claims.getSubject();
                Boolean admin = claims.get("admin", Boolean.class);

                // 解析角色和权限
                List<String> roles = claims.get("roles", List.class);
                List<String> permissions = claims.get("permissions", List.class);
                if (roles == null) roles = List.of();
                if (permissions == null) permissions = List.of();

                // 设置租户上下文
                TenantContext.setTenantId(tenantId);

                // 设置用户上下文
                Set<String> permSet = new HashSet<>(permissions);
                UserContext.setCurrentUser(UserContext.CurrentUser.builder()
                        .userId(userId)
                        .tenantId(tenantId)
                        .username(username)
                        .admin(admin != null && admin)
                        .roles(roles)
                        .permissions(permSet)
                        .build());

                // 设置 Spring Security 上下文
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                if (admin != null && admin) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                }
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
        return StringUtils.hasText(paramToken) ? paramToken : null;
    }
}
