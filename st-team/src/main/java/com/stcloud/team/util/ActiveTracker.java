package com.stcloud.team.util;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stcloud.team.entity.TeamMember;
import com.stcloud.team.mapper.TeamMemberMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 成员活跃追踪工具
 * 通过 Redis SETNX 实现 5 分钟去重，避免高频访问导致频繁 DB 写入
 * Redis 不可用时降级为查库判断时间差
 */
@Slf4j
@Component
public class ActiveTracker {

    private static final String ACTIVE_KEY_PREFIX = "team:active:";
    private static final long DEDUP_SECONDS = 300; // 5分钟去重

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private TeamMemberMapper teamMemberMapper;

    /**
     * 更新成员最后活跃时间（5分钟内同一用户同一空间不重复更新）
     *
     * @param spaceId 空间ID
     * @param userId  用户ID
     */
    public void touchActive(Long spaceId, Long userId) {
        if (spaceId == null || userId == null) return;
        String key = ACTIVE_KEY_PREFIX + spaceId + ":" + userId;
        try {
            // Redis SETNX + 过期时间，存在则跳过
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "1", DEDUP_SECONDS, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(acquired)) {
                updateLastActiveAt(spaceId, userId);
            }
        } catch (Exception e) {
            // Redis 不可用时降级：查库判断是否超过5分钟
            log.warn("Redis 不可用，降级查库判断活跃时间: {}", e.getMessage());
            fallbackTouchActive(spaceId, userId);
        }
    }

    /** Redis 降级方案：查库判断 last_active_at 与当前时间差是否超过5分钟 */
    private void fallbackTouchActive(Long spaceId, Long userId) {
        TeamMember member = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId)
                .eq(TeamMember::getUserId, userId));
        if (member == null) return;
        if (member.getLastActiveAt() == null
                || member.getLastActiveAt().isBefore(LocalDateTime.now().minusSeconds(DEDUP_SECONDS))) {
            updateLastActiveAt(spaceId, userId);
        }
    }

    /** 更新 last_active_at 字段 */
    private void updateLastActiveAt(Long spaceId, Long userId) {
        teamMemberMapper.update(null, new LambdaUpdateWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId)
                .eq(TeamMember::getUserId, userId)
                .set(TeamMember::getLastActiveAt, LocalDateTime.now()));
    }
}