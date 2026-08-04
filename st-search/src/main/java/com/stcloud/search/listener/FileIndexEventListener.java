package com.stcloud.search.listener;

import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 文件索引事件监听器
 * <p>
 * 监听 st-core 发布的 FileIndexEvent，异步执行 ES 索引/删除操作。
 * 索引失败仅记录日志，不影响主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileIndexEventListener {

    private final SearchService searchService;

    @Async
    @EventListener
    public void onFileIndexEvent(FileIndexEvent event) {
        try {
            switch (event.getActionType()) {
                case INDEX -> searchService.indexFile(event.getFileNode());
                case DELETE -> searchService.removeIndex(event.getFileNode().getId());
                case UPDATE_META -> searchService.updateMeta(event.getFileNode());
            }
        } catch (Exception e) {
            log.error("Failed to handle file index event: fileId={}, action={}, error={}",
                    event.getFileNode().getId(), event.getActionType(), e.getMessage());
        }
    }
}
