package com.stcloud.team.service;

import com.stcloud.common.context.UserContext;
import com.stcloud.core.entity.FileNode;
import com.stcloud.team.entity.FileWatch;
import com.stcloud.team.mapper.FileWatchMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 关注幂等写入与取消/重关注代际边界测试。 */
@ExtendWith(MockitoExtension.class)
class FileWatchServiceImplTest {

    @Mock
    private FileWatchMapper watchMapper;
    @Mock
    private FileWatchAccessService accessService;

    private FileWatchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FileWatchServiceImpl(watchMapper, accessService);
        UserContext.setCurrentUser(UserContext.CurrentUser.builder()
                .userId(8L).tenantId(1L).username("watcher").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void putUsesUniqueInsertWithoutPreLookup() {
        FileNode node = node(100L);
        when(accessService.findNode(1L, 100L)).thenReturn(node);
        when(accessService.canViewCurrent(1L, 8L, node)).thenReturn(true);
        when(watchMapper.insert(any(FileWatch.class))).thenAnswer(invocation -> {
            FileWatch created = invocation.getArgument(0);
            created.setId(900L);
            return 1;
        });

        var result = service.watch(100L);

        ArgumentCaptor<FileWatch> captor = ArgumentCaptor.forClass(FileWatch.class);
        verify(watchMapper).insert(captor.capture());
        assertEquals(1L, captor.getValue().getTenantId());
        assertEquals(8L, captor.getValue().getUserId());
        assertEquals(100L, captor.getValue().getNodeId());
        assertEquals(900L, result.getData().getWatchId());
        verify(watchMapper, never()).selectForUpdate(any(), any(), any());
    }

    @Test
    void duplicatePutReadsExistingGenerationAfterConcurrentInsert() {
        FileNode node = node(100L);
        when(accessService.findNode(1L, 100L)).thenReturn(node);
        when(accessService.canViewCurrent(1L, 8L, node)).thenReturn(true);
        when(watchMapper.insert(any(FileWatch.class))).thenThrow(new DuplicateKeyException("duplicate"));
        FileWatch existing = new FileWatch();
        existing.setId(901L);
        existing.setTenantId(1L);
        existing.setUserId(8L);
        existing.setNodeId(100L);
        when(watchMapper.selectForUpdate(1L, 8L, 100L)).thenReturn(existing);

        var result = service.watch(100L);

        assertEquals(901L, result.getData().getWatchId());
        verify(watchMapper).selectForUpdate(1L, 8L, 100L);
    }

    private FileNode node(long id) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setTenantId(1L);
        node.setParentId(0L);
        node.setNodeType(1);
        node.setStatus(0);
        node.setUploadStatus(2);
        node.setOwnerId(8L);
        node.setName("watch.txt");
        node.setPath("/watch.txt");
        return node;
    }
}
