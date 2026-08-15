package com.stcloud.preview;

import com.stcloud.common.config.MyBatisPlusConfig;
import com.stcloud.common.config.MyMetaObjectHandler;
import com.stcloud.common.config.S3StorageConfig;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.preview.service.PreviewService;
import com.stcloud.preview.service.impl.PreviewServiceImpl;
import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * st-preview 集成测试专用启动类。
 * <p>
 * 不使用 @ComponentScan，避免扫描到依赖 Redis/RocketMQ/S3 真实连接的 Service/Config；
 * 仅手动注册被测 PreviewServiceImpl，FileNodeMapper 使用真实 H2 验证 SQL 与表结构，
 * 外部依赖（FileService/StorageService/S3Client/S3Presigner）全部 Mock 隔离。
 */
@Configuration
@EnableAutoConfiguration(exclude = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        RocketMQAutoConfiguration.class,
})
@MapperScan("com.stcloud.core.mapper")
@Import({MyBatisPlusConfig.class, MyMetaObjectHandler.class})
public class PreviewTestApplication {

    @Bean
    PreviewService previewService() {
        return new PreviewServiceImpl();
    }

    @Bean
    FileService fileService() {
        return mock(FileService.class);
    }

    @Bean
    StorageService storageService() {
        return mock(StorageService.class);
    }

    @Bean
    S3StorageConfig s3StorageConfig() {
        S3StorageConfig config = mock(S3StorageConfig.class);
        when(config.getPreviewBucket()).thenReturn("stcloud-preview");
        return config;
    }

    @Bean
    S3Client s3Client() {
        return mock(S3Client.class);
    }

    @Bean
    S3Presigner s3Presigner() {
        return mock(S3Presigner.class);
    }
}
