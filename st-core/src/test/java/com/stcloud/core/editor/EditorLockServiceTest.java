package com.stcloud.core.editor;

import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编辑锁服务单元测试（memory 后端）。
 * 覆盖：编辑标记、保护拦截、多人协同不互斥、保存锁互斥、幂等键（TC-18/19/20 锁部分）。
 */
class EditorLockServiceTest {

    private EditorLockService service;

    @BeforeEach
    void setUp() {
        service = new EditorLockService(null, "memory");
    }

    @Test
    void markEditing_makesFileEditing() {
        service.markEditing(1L, "user-1");
        assertTrue(service.isEditing(1L));
    }

    @Test
    void removeEditingUser_clearsMark() {
        service.markEditing(1L, "user-1");
        service.removeEditingUser(1L, "user-1");
        assertFalse(service.isEditing(1L));
    }

    @Test
    void assertNotEditing_throwsWhenEditing() {
        service.markEditing(1L, "user-1");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertNotEditing(List.of(1L)));
        assertEquals(ResultCode.FILE_EDITING.getCode(), ex.getCode());
    }

    @Test
    void assertNotEditing_passesWhenNotEditing() {
        assertDoesNotThrow(() -> service.assertNotEditing(List.of(1L)));
    }

    @Test
    void multiUser_coeditDoesNotBlockEachOther() {
        // 两个用户同时打开：标记集合两条，互不互斥（P2 协同决策）
        service.markEditing(1L, "user-1");
        service.markEditing(1L, "user-2");
        assertTrue(service.isEditing(1L));
        // user-1 关闭后 user-2 仍在编辑
        service.removeEditingUser(1L, "user-1");
        assertTrue(service.isEditing(1L));
        service.removeEditingUser(1L, "user-2");
        assertFalse(service.isEditing(1L));
    }

    @Test
    void saveLock_isExclusiveAndReleasable() {
        assertTrue(service.tryAcquireSaveLock(1L));
        assertFalse(service.tryAcquireSaveLock(1L), "同文件保存锁应互斥");
        service.releaseSaveLock(1L);
        assertTrue(service.tryAcquireSaveLock(1L), "释放后可再次获取");
    }

    @Test
    void saveLock_differentFilesNotBlocked() {
        assertTrue(service.tryAcquireSaveLock(1L));
        assertTrue(service.tryAcquireSaveLock(2L), "不同文件保存锁互不影响");
    }

    @Test
    void dedup_markThenHit() {
        assertFalse(service.isSaveDeduped("k1"));
        service.markSaveDedup("k1", 60);
        assertTrue(service.isSaveDeduped("k1"));
        assertFalse(service.isSaveDeduped("k2"));
    }
}
