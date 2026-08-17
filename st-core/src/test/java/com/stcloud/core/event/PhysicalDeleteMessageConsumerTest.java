package com.stcloud.core.event;

import com.stcloud.core.entity.FileNode;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.StorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * PhysicalDeleteMessageConsumer 单元测试（事务边界治理 F4）：
 * 覆盖 objectId 路径调用 deletePhysical、旧数据（无 objectId）按 storagePath 删除、
 * 删除失败仅记录日志不重抛、空消息/缺键消息忽略。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("物理删除 MQ 消费者测试")
class PhysicalDeleteMessageConsumerTest {

    @Mock
    private FileObjectService fileObjectService;

    @Mock
    private StorageService storageService;

    @InjectMocks
    private PhysicalDeleteMessageConsumer consumer;

    private EventMessage buildMessage(Long objectId, String storagePath) {
        FileNode node = new FileNode();
        node.setId(1L);
        node.setTenantId(1L);
        node.setObjectId(objectId);
        node.setStoragePath(storagePath);
        return EventMessage.fromPhysicalDelete(node, 99L);
    }

    @Test
    @DisplayName("objectId 路径 - 调用 fileObjectService.deletePhysical")
    void objectIdPath_callsDeletePhysical() {
        consumer.onMessage(buildMessage(10L, "t1/md5-a"));

        verify(fileObjectService).deletePhysical(10L);
        verify(storageService, never()).deleteObject(anyString());
    }

    @Test
    @DisplayName("旧数据无 objectId - 按 storagePath 调用 deleteObject")
    void legacyPath_callsDeleteObject() {
        consumer.onMessage(buildMessage(null, "t1/md5-legacy"));

        verify(storageService).deleteObject("t1/md5-legacy");
        verify(fileObjectService, never()).deletePhysical(anyLong());
    }

    @Test
    @DisplayName("删除抛异常 - 消费者捕获不重抛")
    void deleteFailure_notRethrown() {
        doThrow(new RuntimeException("s3 down"))
                .when(fileObjectService).deletePhysical(anyLong());

        assertDoesNotThrow(() -> consumer.onMessage(buildMessage(10L, "t1/md5-a")));

        verify(fileObjectService).deletePhysical(10L);
    }

    @Test
    @DisplayName("空消息 - 忽略")
    void nullMessage_ignored() {
        consumer.onMessage(null);
        verifyNoInteractions(fileObjectService, storageService);
    }

    @Test
    @DisplayName("缺少 objectId/storagePath - 跳过不删除")
    void missingKeys_skipsDeletion() {
        consumer.onMessage(buildMessage(null, null));
        verifyNoInteractions(fileObjectService, storageService);
    }
}
