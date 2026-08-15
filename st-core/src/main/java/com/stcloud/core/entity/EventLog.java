package com.stcloud.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 事件 Outbox 日志（TASK-004）。
 * 业务事务内写入，事务提交后由 EventRelay 投递 RocketMQ（或本地兜底）；回滚即不产生事件。
 * 投递失败标记 status=2 由定时任务重投；eventLogId 作为消费端幂等键。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("event_log")
public class EventLog extends BaseEntity {

    /** 事件类型：FILE_INDEX / SYNC_CHANGE */
    private String eventType;

    /** 事件负载 JSON（EventMessage） */
    private String payload;

    /** 状态：0-待投递 1-已投递 2-投递失败 */
    private Integer status;

    /** 重试次数 */
    private Integer retryCount;

    /** 成功投递时间 */
    private LocalDateTime processedAt;
}