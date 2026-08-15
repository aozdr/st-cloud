package com.stcloud.share;

import com.stcloud.common.config.MyBatisPlusConfig;
import com.stcloud.common.config.MyMetaObjectHandler;
import com.stcloud.core.editor.EditorConfigService;
import com.stcloud.core.service.DownloadService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.share.service.ShareService;
import com.stcloud.share.service.impl.ShareServiceImpl;
import com.stcloud.team.service.TeamService;
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

import static org.mockito.Mockito.mock;

/**
 * st-share 集成测试专用启动类。
 * <p>
 * 不使用 @ComponentScan，避免扫描到依赖 S3/Redis/RocketMQ 的 Service/Config。
 * 仅手动注册 ShareServiceImpl + @Import 必要的 MyBatis-Plus 配置（租户拦截器 + 自动填充），
 * 并 Mock 分享链路依赖的外部服务（FileService/DownloadService/StorageService），
 * FileShareMapper/FileNodeMapper 使用真实 H2 验证 SQL 与表结构。
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
@MapperScan({"com.stcloud.share.mapper", "com.stcloud.core.mapper"})
@Import({MyBatisPlusConfig.class, MyMetaObjectHandler.class})
public class ShareTestApplication {

    @Bean
    ShareService shareService() {
        return new ShareServiceImpl();
    }

    @Bean
    FileService fileService() {
        return mock(FileService.class);
    }

    @Bean
    DownloadService downloadService() {
        return mock(DownloadService.class);
    }

    @Bean
    StorageService storageService() {
        return mock(StorageService.class);
    }

    @Bean
    TeamService teamService() {
        return mock(TeamService.class);
    }

    /** 在线编辑配置服务：分享编辑器 config 端点依赖（编辑器端到端测试由 st-core 覆盖，此处 mock） */
    @Bean
    EditorConfigService editorConfigService() {
        return mock(EditorConfigService.class);
    }
}
