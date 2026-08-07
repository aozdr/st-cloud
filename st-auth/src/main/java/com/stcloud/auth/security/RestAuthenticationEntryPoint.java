package com.stcloud.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.response.Result;
import com.stcloud.common.response.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 未认证（token 缺失/失效）统一返回 401 JSON。
 * <p>Spring Security 6 未配置 AuthenticationEntryPoint 时，对未认证请求默认返回 403，
 * 而前端仅对 401 触发刷新令牌/跳转登录，导致旧 token 失效后页面卡在 403。
 * 显式注入此 EntryPoint 后未认证返回 401，前端可正常刷新或跳转登录页。
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(ResultCode.UNAUTHORIZED)));
    }
}
