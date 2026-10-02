package com.stcloud.core.service.impl;

import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.core.CoreTestApplication;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.UploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = CoreTestApplication.class)
@ActiveProfiles("test")
@Import(UploadTransactionBoundaryTest.TxBoundaryConfig.class)
class ObjectCleanupRaceIntegrationTest extends UploadTransactionBoundaryTest {
    @Autowired UploadService uploads;
    @Autowired StorageService storage;
    @Autowired ReliableEventPublisher events;
    @Autowired JdbcTemplate db;

    @Test
    void failedUploadCannotDeleteConcurrentWinnersPhysicalObject() throws Exception {
        Set<String> objects = ConcurrentHashMap.newKeySet();
        CountDownLatch bothPut = new CountDownLatch(2);
        CountDownLatch loserDone = new CountDownLatch(1);
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            objects.add(key);
            bothPut.countDown();
            assertTrue(bothPut.await(10, TimeUnit.SECONDS));
            if (Thread.currentThread().getName().equals("race-winner")) {
                assertTrue(loserDone.await(10, TimeUnit.SECONDS));
            }
            return null;
        }).when(storage).uploadObject(anyString(), any(InputStream.class), anyLong(), anyString());
        doAnswer(inv -> { objects.remove(inv.getArgument(0)); return null; })
                .when(storage).deleteObject(anyString());
        doAnswer(inv -> {
            FileNode node = inv.getArgument(0);
            if (node.getName().equals("tb-race-loser.txt")) throw new IllegalStateException("injected rollback");
            return null;
        }).when(events).publishFileIndex(any(FileNode.class), any(FileIndexEvent.ActionType.class));
        ExecutorService loser = Executors.newSingleThreadExecutor(r -> new Thread(r, "race-loser"));
        ExecutorService winner = Executors.newSingleThreadExecutor(r -> new Thread(r, "race-winner"));
        try {
            Future<?> failed = loser.submit(() -> {
                user();
                try { assertThrows(RuntimeException.class, () -> upload("tb-race-loser.txt")); }
                finally { loserDone.countDown(); UserContext.clear(); TenantContext.clear(); }
            });
            Future<?> succeeded = winner.submit(() -> {
                user();
                try { upload("tb-race-winner.txt"); }
                finally { UserContext.clear(); TenantContext.clear(); }
            });
            failed.get(15, TimeUnit.SECONDS);
            succeeded.get(15, TimeUnit.SECONDS);
            String path = db.queryForObject("SELECT storage_path FROM file_node WHERE name='tb-race-winner.txt'", String.class);
            assertTrue(objects.contains(path), "成功节点的物理对象不得被失败请求补偿删除");
            assertEquals(1, db.queryForObject("SELECT ref_count FROM file_object WHERE storage_path=?", Integer.class, path));
        } finally {
            loser.shutdownNow(); winner.shutdownNow();
            db.update("DELETE FROM file_object WHERE md5=?", cn.hutool.crypto.digest.DigestUtil.md5Hex("round3-race-content"));
        }
    }
    private void upload(String name) {
        uploads.simpleUpload(0L, new MockMultipartFile("file", name, "text/plain", "round3-race-content".getBytes(java.nio.charset.StandardCharsets.UTF_8)), null);
    }
    private void user() {
        TenantContext.setTenantId(1L); TenantContext.setTenantMode("SAAS");
        UserContext.setCurrentUser(UserContext.CurrentUser.builder().userId(1001L).tenantId(1L).username("race").build());
    }
}
