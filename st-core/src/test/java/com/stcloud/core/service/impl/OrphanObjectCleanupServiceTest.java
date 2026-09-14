package com.stcloud.core.service.impl;

import com.stcloud.core.entity.FileObject;
import com.stcloud.core.entity.OrphanObjectCandidate;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.mapper.OrphanObjectCandidateMapper;
import com.stcloud.core.mapper.UploadSessionMapper;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrphanObjectCleanupServiceTest {

    @Mock
    private OrphanObjectCandidateMapper candidateMapper;
    @Mock
    private FileObjectMapper fileObjectMapper;
    @Mock
    private FileNodeMapper fileNodeMapper;
    @Mock
    private UploadSessionMapper uploadSessionMapper;
    @Mock
    private UploadStorageManager storageManager;

    private OrphanObjectCleanupService service;

    @BeforeEach
    void setUp() {
        service = new OrphanObjectCleanupService();
        ReflectionTestUtils.setField(service, "candidateMapper", candidateMapper);
        ReflectionTestUtils.setField(service, "fileObjectMapper", fileObjectMapper);
        ReflectionTestUtils.setField(service, "fileNodeMapper", fileNodeMapper);
        ReflectionTestUtils.setField(service, "uploadSessionMapper", uploadSessionMapper);
        ReflectionTestUtils.setField(service, "storageManager", storageManager);
        ReflectionTestUtils.setField(service, "graceMs", 3_600_000L);
    }

    @Test
    void failedUploadOnlyLeavesCandidateAndNeverDeletesImmediately() {
        when(candidateMapper.insertIfAbsent(any())).thenReturn(1);
        service.beginUpload(1L, "md5-a", "1/md5-a");
        verify(candidateMapper).insertIfAbsent(any(OrphanObjectCandidate.class));

        OrphanObjectCandidate candidate = new OrphanObjectCandidate();
        candidate.setId(7L);
        when(candidateMapper.selectByTenantAndPath(1L, "1/md5-a")).thenReturn(candidate);
        service.markFailed(1L, "1/md5-a");

        verify(candidateMapper).decrementActive(7L);
        verify(candidateMapper).markPendingIfInactive(7L);
        verifyNoInteractions(storageManager);
    }

    @Test
    void dueCandidateIsDeletedOnlyAfterAllSafetyChecksPass() {
        OrphanObjectCandidate candidate = candidate(9L, 1L, "md5-b", "1/md5-b");
        when(candidateMapper.selectDue(any(), eq(100))).thenReturn(List.of(candidate));
        when(candidateMapper.claimForDelete(eq(9L), any())).thenReturn(1);
        when(fileObjectMapper.selectByTenantAndMd5(1L, "md5-b")).thenReturn(null);
        when(fileNodeMapper.countValidRefsByTenantAndPath(1L, "md5-b", "1/md5-b")).thenReturn(0L);
        when(uploadSessionMapper.countActiveByTenantAndPath(1L, "1/md5-b")).thenReturn(0L);
        when(storageManager.deleteObjectQuietly("1/md5-b")).thenReturn(true);

        service.scheduledCleanup();

        verify(storageManager).deleteObjectQuietly("1/md5-b");
        verify(candidateMapper).deleteCandidate(9L);
    }

    @Test
    void normalObjectFoundDuringRecheckMustKeepCandidate() {
        OrphanObjectCandidate candidate = candidate(10L, 1L, "md5-c", "1/md5-c");
        when(candidateMapper.selectDue(any(), eq(100))).thenReturn(List.of(candidate));
        when(candidateMapper.claimForDelete(eq(10L), any())).thenReturn(1);
        when(fileObjectMapper.selectByTenantAndMd5(1L, "md5-c")).thenReturn(new FileObject());
        when(fileNodeMapper.countValidRefsByTenantAndPath(1L, "md5-c", "1/md5-c")).thenReturn(0L);
        when(uploadSessionMapper.countActiveByTenantAndPath(1L, "1/md5-c")).thenReturn(0L);

        service.scheduledCleanup();

        verify(candidateMapper).releaseDelete(10L);
        verify(storageManager, never()).deleteObjectQuietly(anyString());
        verify(candidateMapper, never()).deleteCandidate(10L);
    }

    private static OrphanObjectCandidate candidate(Long id, Long tenantId, String md5, String path) {
        OrphanObjectCandidate value = new OrphanObjectCandidate();
        value.setId(id);
        value.setTenantId(tenantId);
        value.setMd5(md5);
        value.setStoragePath(path);
        return value;
    }
}
