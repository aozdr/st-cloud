package com.stcloud.common.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 信用桶实现。
 * <p>
 * 时间基准取 Redis TIME（不信任节点本地时钟）；令牌用「毫字节」整数存储，
 * 规避 Lua 返回值被截断为整数的问题。
 */
@Component
@ConditionalOnProperty(name = "stcloud.rate-limit.mode", havingValue = "redis")
public class RedisCreditStore implements CreditStore {

    private final StringRedisTemplate redis;

    public RedisCreditStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 公共的补充 + 扣减逻辑，被下面两个脚本复用。 */
    private static final String GRANT_BODY = """
            local now = redis.call('TIME')
            local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
            local needMb = tonumber(ARGV[1]) * 1000
            local rate = tonumber(ARGV[2])
            local capMb = tonumber(ARGV[3]) * 1000
            local initMb = tonumber(ARGV[4]) * 1000
            local ver = tonumber(ARGV[5])
            local ttl = tonumber(ARGV[6])
            if rate <= 0 then return {1, 0, 0, 0, 0} end
            local h = redis.call('HMGET', KEYS[1], 'tokensMb', 'lastMs', 'rate', 'ver')
            local tokensMb = tonumber(h[1])
            local storedVer = tonumber(h[4])
            if tokensMb == nil then
              tokensMb = initMb
              redis.call('HSET', KEYS[1], 'tokensMb', math.floor(tokensMb), 'lastMs', nowMs, 'rate', rate, 'ver', ver)
              storedVer = ver
            else
              if ver < storedVer then
                return {0, 0, tonumber(h[3]), storedVer, 1}
              end
              if ver == storedVer and rate ~= tonumber(h[3]) then
                return {0, 0, tonumber(h[3]), storedVer, 1}
              end
              local elapsed = nowMs - tonumber(h[2])
              if elapsed < 0 then elapsed = 0 end
              tokensMb = tokensMb + elapsed * tonumber(h[3])
              if tokensMb > capMb then tokensMb = capMb end
              local storedRate = tonumber(h[3])
              if ver > storedVer then storedRate = rate end
              rate = storedRate
              redis.call('HSET', KEYS[1], 'lastMs', nowMs, 'rate', storedRate, 'ver', ver)
              storedVer = ver
            end
            local granted = 0
            local waitMs = 0
            if tokensMb >= needMb then
              tokensMb = tokensMb - needMb
              granted = 1
            else
              waitMs = math.ceil((needMb - tokensMb) / rate)
            end
            redis.call('HSET', KEYS[1], 'tokensMb', math.floor(tokensMb))
            redis.call('PEXPIRE', KEYS[1], ttl)
            """.stripIndent();

    private static final String GRANT_LUA = GRANT_BODY + "return {granted, waitMs, rate, storedVer, 0}";

    private static final String UPLOAD_LUA = """
            local now = redis.call('TIME')
            local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
            local windowKey = KEYS[2]
            local windowSize = tonumber(ARGV[7])
            if windowSize > 0 then
              redis.call('ZREMRANGEBYSCORE', windowKey, '-inf', nowMs)
              if redis.call('ZCARD', windowKey) >= windowSize then
                return {0, 500, 0, 0, 0}
              end
            end
            local needMb = tonumber(ARGV[1]) * 1000
            local rate = tonumber(ARGV[2])
            local capMb = tonumber(ARGV[3]) * 1000
            local initMb = tonumber(ARGV[4]) * 1000
            local ver = tonumber(ARGV[5])
            local ttl = tonumber(ARGV[6])
            local h = redis.call('HMGET', KEYS[1], 'tokensMb', 'lastMs', 'rate', 'ver')
            local tokensMb = tonumber(h[1])
            local storedVer = tonumber(h[4])
            if tokensMb == nil then
              tokensMb = initMb
              redis.call('HSET', KEYS[1], 'tokensMb', math.floor(tokensMb), 'lastMs', nowMs, 'rate', rate, 'ver', ver)
              storedVer = ver
            else
              if ver < storedVer then
                return {0, 0, tonumber(h[3]), storedVer, 1}
              end
              if ver == storedVer and rate ~= tonumber(h[3]) then
                return {0, 0, tonumber(h[3]), storedVer, 1}
              end
              local elapsed = nowMs - tonumber(h[2])
              if elapsed < 0 then elapsed = 0 end
              tokensMb = tokensMb + elapsed * tonumber(h[3])
              if tokensMb > capMb then tokensMb = capMb end
              local storedRate = tonumber(h[3])
              if ver > storedVer then storedRate = rate end
              rate = storedRate
              redis.call('HSET', KEYS[1], 'lastMs', nowMs, 'rate', storedRate, 'ver', ver)
              storedVer = ver
            end
            local granted = 0
            local waitMs = 0
            if tokensMb >= needMb then
              tokensMb = tokensMb - needMb
              granted = 1
            else
              waitMs = math.ceil((needMb - tokensMb) / rate)
            end
            redis.call('HSET', KEYS[1], 'tokensMb', math.floor(tokensMb))
            redis.call('PEXPIRE', KEYS[1], ttl)
            if granted == 1 and windowSize > 0 then
              redis.call('ZADD', windowKey, nowMs + tonumber(ARGV[8]), ARGV[9])
              redis.call('PEXPIRE', windowKey, ttl)
            end
            return {granted, waitMs, rate, storedVer, 0}
            """.stripIndent();

    private static final DefaultRedisScript<List> GRANT_SCRIPT =
            new DefaultRedisScript<>(GRANT_LUA, List.class);
    private static final DefaultRedisScript<List> UPLOAD_SCRIPT =
            new DefaultRedisScript<>(UPLOAD_LUA, List.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>("return redis.call('ZREM', KEYS[1], ARGV[1])", Long.class);

    @Override
    public CreditGrant grant(String bucketKey, long needBytes, long rateBps, long capacityBytes,
                             long initBytes, long ver, long ttlMs) {
        return execute(GRANT_SCRIPT, bucketKey,
                String.valueOf(needBytes), String.valueOf(rateBps), String.valueOf(capacityBytes),
                String.valueOf(initBytes), String.valueOf(ver), String.valueOf(ttlMs));
    }

    @Override
    public CreditGrant acquireUpload(String bucketKey, String windowKey, long chunkBytes, long rateBps,
                                     long capacityBytes, long initBytes, long ver, long ttlMs,
                                     int windowSize, String chunkKey, long windowTtlMs) {
        List<Long> result = redis.execute(UPLOAD_SCRIPT, List.of(bucketKey, windowKey),
                String.valueOf(chunkBytes), String.valueOf(rateBps), String.valueOf(capacityBytes),
                String.valueOf(initBytes), String.valueOf(ver), String.valueOf(ttlMs),
                String.valueOf(windowSize), String.valueOf(windowTtlMs), chunkKey);
        return toGrant(result);
    }

    @Override
    public boolean releaseWindow(String windowKey, String chunkKey) {
        Long removed = redis.execute(RELEASE_SCRIPT, List.of(windowKey), chunkKey);
        return removed != null && removed > 0;
    }

    private CreditGrant execute(DefaultRedisScript<List> script, String key, String... args) {
        List<Long> result = redis.execute(script, List.of(key), (Object[]) args);
        return toGrant(result);
    }

    private CreditGrant toGrant(List<Long> result) {
        if (result == null || result.size() < 4) {
            return CreditGrant.denied(100L);
        }
        long granted = value(result.get(0));
        long waitMs = value(result.get(1));
        long rate = value(result.get(2));
        long ver = value(result.get(3));
        boolean stale = result.size() > 4 && value(result.get(4)) > 0;
        return new CreditGrant(granted > 0, waitMs, rate, ver, stale);
    }

    private long value(Object object) {
        return object instanceof Number number ? number.longValue() : 0L;
    }
}
