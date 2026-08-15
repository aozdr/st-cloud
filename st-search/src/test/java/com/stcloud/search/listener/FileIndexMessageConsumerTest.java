package com.stcloud.search.listener;

import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.EventMessage;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.search.service.SearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/**
 * FileIndexMessageConsumer 单元测试（TASK-004）：覆盖 INDEX/DELETE/UPDATE_META 分发、
 * 异常捕获不重抛、空消息忽略。Mock SearchService，不依赖真实 ES。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文件索引消息消费者测试")
class FileIndexMessageConsumerTest {

    @Mock
    private SearchService searchService;

    @InjectMocks
    private FileIndexMessageConsumer consumer;

    private EventMessage buildMessage(FileIndexEvent.ActionType action) {
        FileNode node = new FileNode();
        node.setId(1L);
        node.setName("test.txt");
        node.setSuffix("txt");
        node.setPath("/test.txt");
        node.setOwnerId(1L);
        node.setNodeType(1);
        return EventMessage.fromFileIndex(node, action, 99L);
    }

    @Test
    @DisplayName("INDEX - 调用 searchService.indexFile")
    void indexAction_callsIndexFile() {
        consumer.onMessage(buildMessage(FileIndexEvent.ActionType.INDEX));

        verify(searchService).indexFile(any(FileNode.class));
        verify(searchService, never()).removeIndex(any());
        verify(searchService, never()).updateMeta(any());
    }

    @Test
    @DisplayName("DELETE - 调用 searchService.removeIndex")
    void deleteAction_callsRemoveIndex() {
        consumer.onMessage(buildMessage(FileIndexEvent.ActionType.DELETE));

        verify(searchService).removeIndex(1L);
        verify(searchService, never()).indexFile(any(FileNode.class));
        verify(searchService, never()).updateMeta(any());
    }

    @Test
    @DisplayName("UPDATE_META - 调用 searchService.updateMeta")
    void updateMetaAction_callsUpdateMeta() {
        consumer.onMessage(buildMessage(FileIndexEvent.ActionType.UPDATE_META));

        verify(searchService).updateMeta(any(FileNode.class));
        verify(searchService, never()).indexFile(any(FileNode.class));
        verify(searchService, never()).removeIndex(any());
    }

    @Test
    @DisplayName("SearchService 抛异常 - 消费者捕获不重抛")
    void exceptionCaught_notRethrown() {
        doThrow(new RuntimeException("ES error"))
                .when(searchService).indexFile(any(FileNode.class));

        assertDoesNotThrow(() -> consumer.onMessage(buildMessage(FileIndexEvent.ActionType.INDEX)));

        verify(searchService).indexFile(any(FileNode.class));
    }

    @Test
    @DisplayName("空消息 - 忽略")
    void nullMessage_ignored() {
        consumer.onMessage(null);
        verifyNoInteractions(searchService);
    }
}