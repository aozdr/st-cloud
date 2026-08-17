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
 * PhysicalDeleteEventListener 单元测试（事务边界治理 F4）：
 * 覆盖 objectId / 旧数据 storagePath 两条删除路径、失败不重抛、缺键跳过。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("物理删除本地兜底监听器测试")
class PhysicalDeleteEventListenerTest {

    @Mock
    private FileObjectService fileObjectService;

    @Mock
    private StorageService storageService;

    @InjectMocks
    private PhysicalDeleteEventListener listener;

    private PhysicalDeleteEvent event(Long objectId, String storagePath) {
        FileNode node = new FileNode();
        node.setId(1L);
        node.setTenantId(1L);
        node.setObjectId(objectId);
        node.setStoragePath(storagePath);
        return new PhysicalDeleteEvent(this, node);
    }

    @Test
    @DisplayName("objectId 路径 - 调用 deletePhysical（幂等）")
    void objectIdPath_callsDeletePhysical() {
        listener.onPhysicalDelete(event(10L, "t1/md5-a"));

        verify(fileObjectService).deletePhysical(10L);
        verify(storageService, never()).deleteObject(anyString());
    }

    @Test
    @DisplayName("旧数据无 objectId - 按 storagePath 删除")
    void legacyPath_callsDeleteObject() {
        listener.onPhysicalDelete(event(null, "t1/md5-legacy"));

        verify(storageService).deleteObject("t1/md5-legacy");
        verify(fileObjectService, never()).deletePhysical(anyLong());
    }

    @Test
    @DisplayName("删除抛异常 - 不重抛，不阻塞主流程")
    void deleteFailure_notRethrown() {
        doThrow(new RuntimeException("s3 down"))
                .when(fileObjectService).deletePhysical(anyLong());

        assertDoesNotThrow(() -> listener.onPhysicalDelete(event(10L, "t1/md5-a")));

        verify(fileObjectService).deletePhysical(10L);
    }

    @Test
    @DisplayName("缺少 objectId/storagePath - 跳过不删除")
    void missingKeys_skipsDeletion() {
        listener.onPhysicalDelete(event(null, null));
        verifyNoInteractions(fileObjectService, storageService);
    }
}
