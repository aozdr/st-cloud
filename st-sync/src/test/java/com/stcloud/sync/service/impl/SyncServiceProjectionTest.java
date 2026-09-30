package com.stcloud.sync.service.impl;

import com.stcloud.common.context.UserContext;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.FileService;
import com.stcloud.sync.dto.SyncDeltaResponse;
import com.stcloud.sync.entity.*;
import com.stcloud.sync.mapper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SyncServiceProjectionTest {
    private final SyncRootMapper roots = mock(SyncRootMapper.class);
    private final SyncChangeLogMapper logs = mock(SyncChangeLogMapper.class);
    private final SyncExclusionMapper exclusions = mock(SyncExclusionMapper.class);
    private final FileService files = mock(FileService.class);
    private final SyncServiceImpl service = new SyncServiceImpl(roots, logs, exclusions,
            mock(SyncConflictMapper.class), mock(FileNodeMapper.class), files);
    private static final long LARGE_ID = 9007199254740993L;

    @BeforeEach void setup() {
        UserContext.setCurrentUser(UserContext.CurrentUser.builder().userId(7L).tenantId(8L).build());
        SyncRoot root = new SyncRoot();
        root.setId(1L); root.setUserId(7L); root.setCloudFolderNodeId(2L);
        when(roots.selectById(1L)).thenReturn(root);
        FileNode folder = new FileNode(); folder.setPath("/root");
        when(files.getNodeByIdAndOwner(2L)).thenReturn(folder);
        when(exclusions.selectList(any())).thenReturn(List.of());
    }
    @AfterEach void cleanup() { UserContext.clear(); }

    private SyncChangeLog event(String type, String oldPath, String path, int nodeType) {
        SyncChangeLog log = new SyncChangeLog();
        log.setId(LARGE_ID); log.setFileNodeId(LARGE_ID + 2); log.setUserId(7L);
        log.setChangeType(type); log.setOldPath(oldPath); log.setPath(path);
        log.setName("new"); log.setNodeType(nodeType);
        return log;
    }

    @ParameterizedTest
    @CsvSource({"MOVE,0", "MOVE,1", "RENAME,0", "RENAME,1"})
    void projectsAllFourScopeDirections(String type, int nodeType) {
        String[][] cases = {{"/root/old", "/root/new", type},
                {"/root/old", "/root-other/new", "DELETE"},
                {"/root-other/old", "/root/new", "CREATE"},
                {"/root-other/old", "/outside/new", "NONE"}};
        for (String[] c : cases) {
            when(logs.selectList(any())).thenReturn(List.of(event(type, c[0], c[1], nodeType)));
            SyncDeltaResponse response = service.delta(1L, 0L, 1).getData();
            assertEquals(LARGE_ID, response.getCursor());
            assertEquals(2, response.getScopeProjectionVersion());
            assertFalse(response.getReconcileRequired());
            if (c[2].equals("NONE")) { assertTrue(response.getChanges().isEmpty()); continue; }
            assertEquals(1, response.getChanges().size());
            var item = response.getChanges().get(0);
            assertEquals(c[2], item.getChangeType());
            assertEquals(nodeType, item.getNodeType());
            assertEquals("9007199254740995", item.getNodeId());
            assertEquals("9007199254740993", item.getLogId());
            assertEquals(c[2].equals("DELETE") ? "/old" : "/new", item.getPath());
            assertEquals(c[2].equals(type) ? "/old" : null, item.getOldPath());
            assertEquals(c[2].equals("DELETE") ? 1 : 0, item.getStatus());
        }
    }

    @ParameterizedTest
    @CsvSource({"/root/old,/root/excluded/new,DELETE", "/root/excluded/deep/old,/root/new,CREATE",
            "/root/excluded/old,/root/excluded/deep/new,NONE", "/root/excluded-more/old,/root/new,MOVE"})
    void projectsExcludedAncestorsWithoutPrefixOvermatch(String oldPath, String path, String expected) {
        SyncExclusion exclusion = new SyncExclusion(); exclusion.setRelativePath("/excluded");
        when(exclusions.selectList(any())).thenReturn(List.of(exclusion));
        when(logs.selectList(any())).thenReturn(List.of(event("MOVE", oldPath, path, 1)));
        var changes = service.delta(1L, 0L, 1).getData().getChanges();
        if (expected.equals("NONE")) assertTrue(changes.isEmpty());
        else { assertEquals(1, changes.size()); assertEquals(expected, changes.get(0).getChangeType()); }
    }

    @ParameterizedTest
    @CsvSource({"MOVE,0", "MOVE,1", "RENAME,0", "RENAME,1"})
    void excludedMovementAndSamePathRename(String type, int nodeType) {
        SyncExclusion exclusion = new SyncExclusion(); exclusion.setRelativePath("/excluded");
        when(exclusions.selectList(any())).thenReturn(List.of(exclusion));
        String[][] cases = {{"/root/plain", "/root/excluded/deep/file", "DELETE"},
                {"/root/excluded/deep/file", "/root/plain", "CREATE"},
                {"/root/excluded/deep/file", "/root/excluded/deep/file", "NONE"},
                {"/root/excluded/deep/file", "/root/excluded/deep/renamed", "NONE"},
                {"/root/plain", "/root/plain", "NONE"},
                {"/root/excluded-more/file", "/root/plain", type}};
        for (String[] row : cases) {
            when(logs.selectList(any())).thenReturn(List.of(event(type, row[0], row[1], nodeType)));
            var response = service.delta(1L, 0L, 1).getData();
            assertEquals(LARGE_ID, response.getCursor());
            if (row[2].equals("NONE")) assertTrue(response.getChanges().isEmpty());
            else {
                assertEquals(1, response.getChanges().size());
                assertEquals(row[2], response.getChanges().get(0).getChangeType());
            }
        }
    }

    @Test void emptyFilteredPageAdvancesByScannedLogAndKeepsNextEvent() {
        List<SyncChangeLog> first = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            var log = event("CREATE", null, i == 500 ? "/root/new" : "/outside/new", 0);
            log.setId(LARGE_ID + i); first.add(log);
        }
        when(logs.selectList(any())).thenReturn(first, List.of(first.get(500)));
        var page1 = service.delta(1L, LARGE_ID - 1, 1).getData();
        assertTrue(page1.getChanges().isEmpty()); assertTrue(page1.getHasMore());
        assertEquals(LARGE_ID + 499, page1.getCursor());
        var page2 = service.delta(1L, page1.getCursor(), 2).getData();
        assertFalse(page2.getHasMore()); assertEquals(LARGE_ID + 500, page2.getCursor());
        assertEquals(1, page2.getChanges().size());
    }

    @Test void ambiguousMovesRequestReconciliationWithoutRootDeletion() {
        for (String oldPath : Arrays.asList(null, "", " ", "/old-root")) {
            var log = event("MOVE", oldPath, "/root", 1);
            if ("/old-root".equals(oldPath)) log.setFileNodeId(2L);
            when(logs.selectList(any())).thenReturn(List.of(log));
            var response = service.delta(1L, 0L, 1).getData();
            assertTrue(response.getReconcileRequired()); assertTrue(response.getChanges().isEmpty());
        }
        verify(roots, never()).deleteById(any(java.io.Serializable.class));
    }
}
