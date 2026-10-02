package com.stcloud.core.service.impl;

import com.stcloud.common.context.TenantContext;
import com.stcloud.core.CoreTestApplication;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.impl.upload.ObjectUploadCandidateService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=CoreTestApplication.class)
@ActiveProfiles("test")
@Import(UploadTransactionBoundaryTest.TxBoundaryConfig.class)
class ObjectUploadCandidateIntegrationTest {
    private static final long TENANT = 9988L;
    @Autowired ObjectUploadCandidateService candidates;
    @Autowired StorageService storage;
    @Autowired FileObjectService objects;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    private TransactionTemplate tx;
    private String md5;
    @BeforeEach void setup() {
        TenantContext.setTenantId(TENANT); TenantContext.setTenantMode("SAAS");
        tx = new TransactionTemplate(transactions); md5 = UUID.randomUUID().toString().replace("-", "");
        reset(storage, objects);
        doAnswer(inv -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return null; })
                .when(storage).deleteObject(anyString());
    }
    @AfterEach void cleanup() {
        for (String table : new String[]{"file_version","file_node","file_object","upload_session","object_upload_candidate"})
            db.update("DELETE FROM " + table + " WHERE tenant_id=?", TENANT);
        TenantContext.clear();
    }
    private String ready() {
        String path = candidates.register(TENANT, md5);
        assertTrue(candidates.finishWrite(TENANT, path)); return path;
    }
    private void expire(String path) {
        db.update("UPDATE object_upload_candidate SET next_check_at='2000-01-01 00:00:00' WHERE storage_path=?", path);
    }
    private String state(String path) {
        return db.queryForObject("SELECT state FROM object_upload_candidate WHERE storage_path=?", String.class, path);
    }
    private void object(String path) {
        db.update("INSERT INTO file_object(tenant_id,md5,size,storage_path,ref_count,status,deleted) VALUES (?,?,1,?,1,0,0)", TENANT,md5,path);
    }
    @Test void trueOrphanIsDeletedButDifferentDedupWinnerIsPreserved() {
        String path = ready(); object(TENANT + "/legacy"); candidates.discard(TENANT,path); expire(path);
        candidates.collectGarbage();
        verify(storage).deleteObject(path); verify(storage,never()).deleteObject(TENANT+"/legacy");
        assertEquals("DELETED",state(path));
    }
    @Test void gcClaimBeforeCommitRejectsBusinessAdoption() {
        String path=ready(); expire(path); candidates.collectGarbage();
        assertThrows(IllegalStateException.class, () -> tx.execute(t -> objects.acquireByPath(TENANT,md5,1,path)));
        assertEquals("DELETED",state(path));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM file_object WHERE tenant_id=?",Long.class,TENANT));
    }
    @Test void businessCommitBeforeGcProtectsAndCanBeReused() {
        String path=ready();
        FileObject first=tx.execute(t -> objects.acquireByPath(TENANT,md5,1,path));
        expire(path); candidates.collectGarbage(); candidates.discard(TENANT,path);
        FileObject next=tx.execute(t -> objects.acquireByPath(TENANT,md5,1,path));
        assertEquals(first.getId(),next.getId()); assertEquals("ADOPTED",state(path));
        assertEquals(2,db.queryForObject("SELECT ref_count FROM file_object WHERE tenant_id=?",Integer.class,TENANT));
        verify(storage,never()).deleteObject(path);
    }
    @Test void rollbackRestoresReadyAndDedupLoserIsDiscardedInCommit() {
        String path=ready();
        assertThrows(IllegalStateException.class, () -> tx.execute(t -> {
            objects.acquireByPath(TENANT,md5,1,path); throw new IllegalStateException("rollback");
        }));
        assertEquals("READY",state(path));
        object(TENANT+"/legacy");
        FileObject winner=tx.execute(t -> objects.acquireByPath(TENANT,md5,1,path));
        assertEquals(TENANT+"/legacy",winner.getStoragePath()); assertEquals("DISCARDED",state(path));
        expire(path); candidates.collectGarbage(); verify(storage).deleteObject(path);
    }
    @Test void deleteUsesFinishedSnapshotTakenBeforeDeleteAndRetriesLatePut() {
        String path=candidates.register(TENANT,md5); expire(path);
        doAnswer(inv -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(candidates.finishWrite(TENANT,path),"GC认领后迟到PUT不得恢复READY");
            return null;
        }).when(storage).deleteObject(path);
        candidates.collectGarbage(); assertEquals("DELETING",state(path));
        doNothing().when(storage).deleteObject(path); expire(path); candidates.collectGarbage();
        assertEquals("DELETED",state(path)); verify(storage,times(2)).deleteObject(path);
    }
    @Test void unknownWriterTombstoneSurvivesRepeatedSuccessfulDeletes() {
        String path=candidates.register(TENANT,md5);
        for(int i=0;i<2;i++) { expire(path); candidates.collectGarbage(); assertEquals("DELETING",state(path)); }
        verify(storage,times(2)).deleteObject(path);
    }
    @Test void failedDeleteRemainsRetryable() {
        String path=ready(); expire(path); doThrow(new IllegalStateException("S3 unavailable")).when(storage).deleteObject(path);
        candidates.collectGarbage(); assertEquals("DELETING",state(path));
        doNothing().when(storage).deleteObject(path); expire(path); candidates.collectGarbage(); assertEquals("DELETED",state(path));
    }
    @Test void exactObjectReferenceProtectsOrphanCandidate() {
        String path=ready(); object(path); expire(path); candidates.collectGarbage(); verify(storage,never()).deleteObject(path);
    }
    @Test void recoverableRecycleNodeAndVersionProtectPhysicalPath() {
        String path=ready();
        db.update("INSERT INTO file_node(tenant_id,parent_id,node_type,name,path,storage_path,status,uploader_id,owner_id) VALUES (?,0,1,'gc-trash','/gc-trash',?,1,1,1)",TENANT,path);
        expire(path); candidates.collectGarbage(); verify(storage,never()).deleteObject(path);
        db.update("DELETE FROM file_node WHERE tenant_id=?",TENANT);
        db.update("INSERT INTO file_version(tenant_id,file_node_id,version_num,file_size,file_md5,storage_path,modifier_id) VALUES (?,1,1,1,?,?,1)",TENANT,md5,path);
        expire(path); candidates.collectGarbage(); verify(storage,never()).deleteObject(path);
        db.update("DELETE FROM file_version WHERE tenant_id=?",TENANT);
        expire(path); candidates.collectGarbage(); verify(storage).deleteObject(path);
    }
    @Test void activeAndFailedMergeableSessionsProtectContent() {
        String path=ready();
        db.update("INSERT INTO upload_session(tenant_id,upload_id,user_id,file_node_id,storage_path,s3_upload_id,file_size,file_md5,total_chunks,chunk_size,status,expires_at) VALUES (?,'gc-session',1,1,'other-path','s3',1,?,1,1,0,'2099-01-01')",TENANT,md5);
        for(int status:new int[]{0,1,4}) {
            db.update("UPDATE upload_session SET status=? WHERE tenant_id=?",status,TENANT);
            expire(path); candidates.collectGarbage(); verify(storage,never()).deleteObject(path);
        }
        db.update("DELETE FROM upload_session WHERE tenant_id=?",TENANT);
        expire(path); candidates.collectGarbage(); verify(storage).deleteObject(path);
    }
    @Test void unknownManagedPathCannotBypassCandidateClaimAndLegacyIsReusable() {
        String forged=TENANT+"/objects/"+md5+"/"+UUID.randomUUID();
        assertThrows(IllegalStateException.class,()->tx.execute(t->objects.acquireByPath(TENANT,md5,1,forged)));
        object(TENANT+"/"+md5);
        assertEquals(TENANT+"/"+md5,tx.execute(t->objects.acquireByPath(TENANT,md5,1,TENANT+"/"+md5)).getStoragePath());
        candidates.collectGarbage(); verify(storage,never()).deleteObject(anyString());
    }

    @Test void longRunningPutRenewsCandidateUntilWriteFinishes() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(candidates,"gracePeriodMs",2000L);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        doAnswer(inv -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            started.countDown(); assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS)); return null;
        }).when(storage).uploadObject(anyString(),any(java.io.InputStream.class),anyLong(),anyString());
        try {
            var upload = executor.submit(() -> candidates.upload(TENANT,md5,new java.io.ByteArrayInputStream(new byte[]{1}),1,"text/plain"));
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            // 超过初始宽限期；定时心跳必须让正在进行的 PUT 保持 WRITING。
            Thread.sleep(2500);
            candidates.collectGarbage();
            assertEquals("WRITING",db.queryForObject("SELECT state FROM object_upload_candidate WHERE tenant_id=?",String.class,TENANT));
            verify(storage,never()).deleteObject(anyString());
            release.countDown(); String path=upload.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("READY",state(path));
        } finally {
            release.countDown(); executor.shutdownNow();
            org.springframework.test.util.ReflectionTestUtils.setField(candidates,"gracePeriodMs",3600000L);
        }
    }

    @Test void uncertainPutFailureKeepsUnfinishedTombstoneAndRegistrationSurvives() {
        doThrow(new IllegalStateException("network timeout")).when(storage).uploadObject(anyString(),any(java.io.InputStream.class),anyLong(),anyString());
        assertThrows(IllegalStateException.class,()->candidates.upload(TENANT,md5,new java.io.ByteArrayInputStream(new byte[]{1}),1,"text/plain"));
        String path=db.queryForObject("SELECT storage_path FROM object_upload_candidate WHERE tenant_id=?",String.class,TENANT);
        assertEquals("DISCARDED",state(path));
        assertEquals(0,db.queryForObject("SELECT write_finished FROM object_upload_candidate WHERE storage_path=?",Integer.class,path));
        expire(path); candidates.collectGarbage(); assertEquals("DELETING",state(path));
        verify(storage).deleteObject(path);
    }
}
