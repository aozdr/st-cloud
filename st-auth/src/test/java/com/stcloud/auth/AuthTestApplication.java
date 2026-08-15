package com.stcloud.auth;

import com.stcloud.auth.service.AuthService;
import com.stcloud.common.config.MyBatisPlusConfig;
import com.stcloud.common.config.MyMetaObjectHandler;
import com.stcloud.common.utils.JwtUtils;
import org.mockito.ArgumentMatchers;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * st-auth 集成测试专用启动类。
 * <p>
 * 不使用 @ComponentScan，避免扫描到依赖真实 Redis/Security 的组件。
 * 仅注册 AuthService + JwtUtils（真实 MyBatis-Plus + H2 + BCrypt），
 * 并 Mock Redis（StringRedisTemplate 仅用于存储/校验 refresh token）。
 * 测试覆盖登录/注册/刷新/当前用户主路径的密码校验与 Token 生成校验。
 */
@Configuration
@EnableAutoConfiguration(exclude = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
})
@MapperScan({"com.stcloud.auth.mapper", "com.stcloud.common.mapper"})
@Import({MyBatisPlusConfig.class, MyMetaObjectHandler.class, AuthService.class, JwtUtils.class})
public class AuthTestApplication {

    /**
     * 暴露 ValueOperations Mock，供测试桩定 refresh token 的 Redis 命中/未命中场景。
     */
    @Bean
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> stringValueOperations() {
        return mock(ValueOperations.class);
    }

    /**
     * Redis 仅承载 refresh token 存取，测试中以 Mock 替代真实连接。
     */
    @Bean
    StringRedisTemplate stringRedisTemplate(ValueOperations<String, String> valueOperations) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.opsForValue()).thenReturn(valueOperations);
        when(template.delete(ArgumentMatchers.anyString())).thenReturn(true);
        return template;
    }
}
