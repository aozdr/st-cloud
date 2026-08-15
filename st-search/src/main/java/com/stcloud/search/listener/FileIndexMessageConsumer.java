package com.stcloud.search.listener;

import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.EventMessage;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 文件索引事件 MQ 消费者（TASK-004）：接收 Outbox 投递的 FILE_INDEX 消息，
 * 异步执行 ES 索引 / 删除 / 元数据更新，与本地 @EventListener 监听器并存（MQ 配置时本消费者生效）。
 * <p>
 * ES 操作天然幂等：INDEX 覆盖写、DELETE 幂等删除、UPDATE_META 覆盖字段，重复投递不会产生重复效果。
 * 消费失败仅记录日志，与本地监听器语义一致（索引失败不影响文件操作主流程）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(topic = "FILE_INDEX", consumerGroup = "stcloud-search")
@RequiredArgsConstructor
public class FileIndexMessageConsumer implements RocketMQListener<EventMessage> {

    private final SearchService searchService;

    @Override
    public void onMessage(EventMessage message) {
        if (message == null || message.getFileNode() == null || message.getFileNode().getId() == null) {
            log.warn("收到空文件索引消息，忽略");
            return;
        }
        try {
            FileNode node = message.getFileNode().toFileNode();
            switch (FileIndexEvent.ActionType.valueOf(message.getActionType())) {
                case INDEX -> searchService.indexFile(node);
                case DELETE -> searchService.removeIndex(node.getId());
                case UPDATE_META -> searchService.updateMeta(node);
            }
        } catch (Exception e) {
            log.error("处理文件索引 MQ 消息失败: eventLogId={}, action={}, error={}",
                    message.getEventLogId(), message.getActionType(), e.getMessage());
        }
    }
}
