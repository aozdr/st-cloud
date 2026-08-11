package com.stcloud.core;

import com.stcloud.common.config.MyBatisPlusConfig;
import com.stcloud.common.config.MyMetaObjectHandler;
import com.stcloud.core.service.impl.FavoriteServiceImpl;
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

/**
 * st-core 集成测试专用启动类。
 * <p>
 * 不使用 @ComponentScan，避免扫描到依赖 S3/Redis/RocketMQ 的 Service/Config（如 FileServiceImpl、StorageInitializer）。
 * 仅手动注册被测组件 + @Import 必要的 MyBatis-Plus 配置（租户拦截器 + 自动填充）。
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
@MapperScan("com.stcloud.core.mapper")
@Import({MyBatisPlusConfig.class, MyMetaObjectHandler.class})
public class CoreTestApplication {

    @Bean
    FavoriteServiceImpl favoriteService() {
        return new FavoriteServiceImpl();
    }
}
