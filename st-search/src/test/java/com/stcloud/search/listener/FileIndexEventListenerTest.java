package com.stcloud.search.listener;

import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.search.service.SearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

/**
 * FileIndexEventListener 单元测试
 * <p>
 * 验证事件监听器正确分发 INDEX/DELETE/UPDATE_META 三种事件到 SearchService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文件索引事件监听器测试")
class FileIndexEventListenerTest {

    @Mock
    private SearchService searchService;

    @InjectMocks
    private FileIndexEventListener listener;

    private FileNode buildFileNode() {
        FileNode node = new FileNode();
        node.setId(1L);
        node.setName("test.txt");
        node.setSuffix("txt");
        node.setPath("/test.txt");
        node.setOwnerId(1L);
        node.setNodeType(NodeType.FILE.getCode());
        node.setStatus(NodeStatus.NORMAL.getCode());
        return node;
    }

    @Test
    @DisplayName("INDEX 事件 - 调用 searchService.indexFile")
    void testIndexEvent() {
        FileNode node = buildFileNode();
        FileIndexEvent event = new FileIndexEvent(this, node, FileIndexEvent.ActionType.INDEX);

        listener.onFileIndexEvent(event);

        verify(searchService).indexFile(node);
        verify(searchService, never()).removeIndex(any());
        verify(searchService, never()).updateMeta(any());
    }

    @Test
    @DisplayName("DELETE 事件 - 调用 searchService.removeIndex")
    void testDeleteEvent() {
        FileNode node = buildFileNode();
        FileIndexEvent event = new FileIndexEvent(this, node, FileIndexEvent.ActionType.DELETE);

        listener.onFileIndexEvent(event);

        verify(searchService).removeIndex(1L);
        verify(searchService, never()).indexFile(any());
        verify(searchService, never()).updateMeta(any());
    }

    @Test
    @DisplayName("UPDATE_META 事件 - 调用 searchService.updateMeta")
    void testUpdateMetaEvent() {
        FileNode node = buildFileNode();
        FileIndexEvent event = new FileIndexEvent(this, node, FileIndexEvent.ActionType.UPDATE_META);

        listener.onFileIndexEvent(event);

        verify(searchService).updateMeta(node);
        verify(searchService, never()).indexFile(any());
        verify(searchService, never()).removeIndex(any());
    }

    @Test
    @DisplayName("SearchService 抛出异常时 - 监听器不抛出（异常被捕获）")
    void testExceptionHandled() {
        FileNode node = buildFileNode();
        FileIndexEvent event = new FileIndexEvent(this, node, FileIndexEvent.ActionType.INDEX);

        doThrow(new RuntimeException("ES error"))
                .when(searchService).indexFile(node);

        // 不应抛出异常
        listener.onFileIndexEvent(event);

        verify(searchService).indexFile(node);
    }
}
