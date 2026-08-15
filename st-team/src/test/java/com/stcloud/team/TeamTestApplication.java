package com.stcloud.team;

import com.stcloud.common.config.MyBatisPlusConfig;
import com.stcloud.common.config.MyMetaObjectHandler;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.impl.FileServiceImpl;
import com.stcloud.team.service.FolderPermissionService;
import com.stcloud.team.service.TeamService;
import com.stcloud.team.service.impl.TeamServiceImpl;
import com.stcloud.team.util.ActiveTracker;
import com.stcloud.team.util.NotificationHelper;
import com.stcloud.team.util.TeamActivityHelper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * st-team 集成测试专用启动类。
 * <p>
 * 不使用 @ComponentScan，避免扫描到依赖 Redis/外部服务的 Config；
 * 仅手动注册被测 TeamServiceImpl + 真实 FolderPermissionService/NotificationHelper，
 * Mapper 使用真实 H2 验证 SQL 与表结构（team/auth/core），
 * 外部依赖（CloudStorageService/ActiveTracker/TeamActivityHelper）Mock 隔离。
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
@MapperScan({"com.stcloud.team.mapper", "com.stcloud.core.mapper", "com.stcloud.auth.mapper"})
@Import({MyBatisPlusConfig.class, MyMetaObjectHandler.class})
public class TeamTestApplication {

    @Bean
    TeamService teamService() {
        return new TeamServiceImpl();
    }

    @Bean
    FolderPermissionService folderPermissionService() {
        return new FolderPermissionService();
    }

    @Bean
    CloudStorageService cloudStorageService() {
        return mock(CloudStorageService.class);
    }

    @Bean
    ReliableEventPublisher reliableEventPublisher() {
        return mock(ReliableEventPublisher.class);
    }

    @Bean
    FileObjectService fileObjectService() {
        return mock(FileObjectService.class);
    }

    @Bean
    FileService fileService() {
        // 真实实现：团队锁定校验依赖 getTeamNodeById -> validateTeamNode/validateAccessible/toVO，
        // 仅使用 FileNodeMapper 与内存缓存；其余服务依赖由上方 Mock 提供，避免拉入 Redis/MQ 配置。
        return new FileServiceImpl();
    }

    @Bean
    ActiveTracker activeTracker() {
        return mock(ActiveTracker.class);
    }

    @Bean
    StringRedisTemplate stringRedisTemplate() {
        // ActiveTracker 以 @Resource 注入 StringRedisTemplate（含 Mockito mock 继承字段），
        // 提供候选 bean 避免上下文启动失败
        return mock(StringRedisTemplate.class);
    }

    @Bean
    TeamActivityHelper teamActivityHelper() {
        return mock(TeamActivityHelper.class);
    }

    @Bean
    NotificationHelper notificationHelper() {
        // 真实实现：邀请成员/评论@等通知写入走 H2 notification 表
        return new NotificationHelper();
    }
}
