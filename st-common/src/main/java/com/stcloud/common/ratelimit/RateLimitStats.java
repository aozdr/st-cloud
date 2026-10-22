package com.stcloud.common.ratelimit;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 限速运行指标（进程级）。用于排查多节点超额与 Redis 降级，
 * 通过管理端只读接口暴露，不引入额外监控依赖。
 */
@Component
public class RateLimitStats {

    private final LongAdder granted = new LongAdder();
    private final LongAdder denied = new LongAdder();
    private final LongAdder stale = new LongAdder();
    private final LongAdder degraded = new LongAdder();
    private final LongAdder storeErrors = new LongAdder();

    public void granted() {
        granted.increment();
    }

    public void denied() {
        denied.increment();
    }

    public void stale() {
        stale.increment();
    }

    public void degraded() {
        degraded.increment();
    }

    public void storeError() {
        storeErrors.increment();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("granted", granted.sum());
        map.put("denied", denied.sum());
        map.put("stale", stale.sum());
        map.put("degraded", degraded.sum());
        map.put("storeErrors", storeErrors.sum());
        return map;
    }
}
