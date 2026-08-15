package com.stcloud.core.outbox;

import com.stcloud.core.mapper.EventLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 事件 Outbox 清理定时任务（TASK-002）：定期删除超过保留期的历史 event_log 行，控制表增长。
 * <p>
 * 清理规则（仅删除可安全清除的行）：
 * <ul>
 *   <li>status=1（已投递，含本地兜底已标记）：processed_at 超过保留期 → 删除（投递已完成，消费端幂等）</li>
 *   <li>status=2 且 retry_count 已达上限（重试耗尽）：created_at 超过保留期 → 删除（不再被重投选中）</li>
 *   <li>status=0（在途/未投递）与 status=2 未耗尽（仍可重试）永不清理</li>
 * </ul>
 * 保留天数可配置：app.event-log.retention-days（默认 30）；可关闭：app.event-log.cleanup-enabled=false。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.event-log.cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class EventLogCleanupTask {

    /** 清理周期：24 小时 */
    private static final long CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000L;

    private final EventLogMapper eventLogMapper;

    /** 保留天数：默认 30 天 */
    @Value("${app.event-log.retention-days:30}")
    private long retentionDays;

    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MS, initialDelay = CLEANUP_INTERVAL_MS)
    public void cleanupExpiredEvents() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int removed = eventLogMapper.cleanupExpired(cutoff, EventRetryTask.MAX_RETRY);
        if (removed > 0) {
            log.info("事件 Outbox 清理完成: removed={}, cutoff={}, retentionDays={}", removed, cutoff, retentionDays);
        }
    }
}
