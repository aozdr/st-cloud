package com.stcloud.sync.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 同步推送服务
 * <p>
 * 在变更日志写入后，向该文件所有者的所有在线 WebSocket 会话推送变更通知。
 * 客户端收到通知后立即拉取 delta，实现近实时同步。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncPushService {

    private final SyncWebSocketHandler webSocketHandler;

    /**
     * 向文件所有者推送变更通知
     *
     * @param userId 文件所有者 ID
     * @param logId  变更日志 ID（客户端可作为 since 游标参考）
     */
    public void pushChangeNotification(Long userId, Long logId) {
        if (userId == null) {
            return;
        }
        // 构造推送消息：JSON 格式，客户端解析 event 字段决定动作
        String message = String.format(
                "{\"event\":\"change\",\"userId\":%d,\"logId\":%d}", userId, logId);

        int sent = webSocketHandler.sendToUser(userId, message);
        if (sent > 0) {
            log.debug("同步变更通知已推送：userId={}, logId={}, 设备数={}", userId, logId, sent);
        }
    }
}