package com.stcloud.core.task;

import com.stcloud.core.service.RecycleBinService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 回收站自动清理定时任务：每日 03:00 清理超过保留期的回收站节点。
 *
 * <p>多实例部署下通过 Redis 分布式锁保证只有一个实例执行，避免重复退配额。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecycleBinPurgeTask {

    private static final String LOCK_KEY = "stcloud:lock:recycle-purge";
    private static final long LOCK_TTL_MINUTES = 30;

    private final RecycleBinService recycleBinService;
    private final StringRedisTemplate stringRedisTemplate;

    /** 释放锁的 Lua 脚本：仅当 value 匹配时删除，避免误删其它实例持有的锁 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Scheduled(cron = "0 0 3 * * ?")
    public void purgeExpiredRecycleBin() {
        String token = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, token, LOCK_TTL_MINUTES, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("回收站自动清理：其它实例正在执行，跳过本次");
            return;
        }
        try {
            List<Long> ids = recycleBinService.findExpiredRecycleRoots();
            log.info("回收站自动清理：待清理 {} 个过期节点", ids.size());
            int success = 0;
            int failure = 0;
            for (Long id : ids) {
                try {
                    recycleBinService.purgeNode(id);
                    success++;
                } catch (Exception e) {
                    failure++;
                    log.warn("回收站自动清理：节点 {} 清理失败: {}", id, e.getMessage());
                }
            }
            log.info("回收站自动清理完成：成功 {}，失败 {}", success, failure);
        } finally {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(LOCK_KEY), token);
        }
    }
}
