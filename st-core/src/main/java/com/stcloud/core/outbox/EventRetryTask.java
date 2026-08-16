package com.stcloud.core.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.core.entity.EventLog;
import com.stcloud.core.event.EventMessage;
import com.stcloud.core.mapper.EventLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件失败重投定时任务（TASK-004）：周期扫描失败(status=2)或异步投递前崩溃遗留的 PENDING 超时行，
 * 重新投递 RocketMQ。重投成功标记 status=1；重试次数耗尽后不再选中并记录告警日志。
 * <p>
 * 仅当 rocketmq.name-server 配置时启用；多实例部署下同一行可能被重复处理，
 * 但消费端按 event_log_id 幂等，重复投递不会产生重复业务效果。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RequiredArgsConstructor
public class EventRetryTask {

    /** 单条事件最大重试次数（包内可见，供 EventLogCleanupTask 复用） */
    static final int MAX_RETRY = 5;
    /** 每轮扫描上限，避免一次拉取过多阻塞调度线程 */
    private static final int BATCH_LIMIT = 100;
    /** 扫描间隔：60 秒 */
    private static final long RETRY_INTERVAL_MS = 60_000;
    /** PENDING 在途事件卡死阈值：异步投递线程在途或发送前崩溃遗留时兜底重投 */
    private static final long PENDING_STUCK_MINUTES = 5;

    private final EventLogMapper eventLogMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = RETRY_INTERVAL_MS)
    public void retryFailedEvents() {
        LocalDateTime stuckBefore = LocalDateTime.now().minusMinutes(PENDING_STUCK_MINUTES);
        List<EventLog> failedEvents = eventLogMapper.selectRetryable(MAX_RETRY, BATCH_LIMIT, stuckBefore);
        for (EventLog outbox : failedEvents) {
            try {
                EventMessage message = objectMapper.readValue(outbox.getPayload(), EventMessage.class);
                rocketMQTemplate.syncSend(topicOf(outbox.getEventType()), message);
                eventLogMapper.markSent(outbox.getId());
                log.info("事件重投成功: id={}, type={}, retry={}", outbox.getId(), outbox.getEventType(), outbox.getRetryCount() + 1);
            } catch (Exception e) {
                // markFailed 已累加 retry_count；达上限后不再被 selectRetryable 选中
                eventLogMapper.markFailed(outbox.getId());
                log.error("事件重投失败: id={}, type={}, retry={}, error={}",
                        outbox.getId(), outbox.getEventType(), outbox.getRetryCount() + 1, e.getMessage());
            }
        }
    }

    /** 事件类型即主题：FILE_INDEX / SYNC_CHANGE */
    private String topicOf(String eventType) {
        return eventType;
    }
}
