package com.stcloud.team.util;

import com.stcloud.auth.entity.SysUser;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.team.entity.TeamActivity;
import com.stcloud.team.mapper.TeamActivityMapper;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 团队空间活动日志异步写入工具
 * 复用审计日志的异步线程池模式，避免阻塞请求线程
 */
@Slf4j
@Component
public class TeamActivityHelper {

    @Resource
    private TeamActivityMapper teamActivityMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    /** 活动日志写入线程池 */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "team-activity-writer");
        t.setDaemon(true);
        return t;
    });

    /**
     * 异步记录空间活动日志
     *
     * @param spaceId    空间ID
     * @param action     操作类型（FILE_UPLOAD / MEMBER_JOIN / SPACE_UPDATE 等）
     * @param targetType 目标类型（FILE/FOLDER/MEMBER/SPACE/INVITE）
     * @param targetId   目标ID
     * @param targetName 目标名称
     */
    public void log(Long spaceId, String action, String targetType,
                    Long targetId, String targetName) {
        Long userId = UserContext.getUserId();
        executor.execute(() -> {
            try {
                TeamActivity activity = new TeamActivity();
                activity.setSpaceId(spaceId);
                activity.setUserId(userId);
                // 冗余用户名/昵称，便于前端展示且避免频繁关联查询
                if (userId != null) {
                    SysUser user = sysUserMapper.selectById(userId);
                    if (user != null) {
                        activity.setUsername(user.getUsername());
                        activity.setNickname(user.getNickname());
                    }
                }
                activity.setAction(action);
                activity.setTargetType(targetType);
                activity.setTargetId(targetId);
                activity.setTargetName(targetName);
                activity.setCreatedAt(LocalDateTime.now());
                teamActivityMapper.insert(activity);
            } catch (Exception e) {
                log.error("异步写入团队活动日志失败: spaceId={}, action={}", spaceId, action, e);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}