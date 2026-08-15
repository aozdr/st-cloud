package com.stcloud.admin;

import com.stcloud.admin.service.impl.SpeedLimitManageServiceImpl;
import com.stcloud.common.config.MyBatisPlusConfig;
import com.stcloud.common.config.MyMetaObjectHandler;
import com.stcloud.common.ratelimit.SpeedLimitCache;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * st-admin 集成测试专用启动类。
 * <p>
 * 不使用 @ComponentScan，避免扫描到依赖 S3/Redis/Security 的组件。
 * 仅注册被测组件（SpeedLimitManageServiceImpl + SpeedLimitCache）与 MyBatis-Plus 配置，
 * 使用真实 H2 验证审计日志写入/查询与限速配置主路径的 SQL 与表结构。
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
@MapperScan({"com.stcloud.admin.mapper", "com.stcloud.common.mapper"})
@Import({MyBatisPlusConfig.class, MyMetaObjectHandler.class,
        SpeedLimitCache.class, SpeedLimitManageServiceImpl.class})
public class AdminTestApplication {
}
