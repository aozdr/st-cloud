package com.stcloud.sync.listener;

import com.stcloud.common.context.TenantContext;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.EventMessage;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.sync.entity.SyncChangeLog;
import com.stcloud.sync.mapper.SyncChangeLogMapper;
import com.stcloud.sync.ws.SyncPushService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SyncTenantScopeTest {
    @AfterEach void clear() { TenantContext.clear(); }

    private FileNode node(long tenant) {
        FileNode node = new FileNode(); node.setId(800L); node.setTenantId(tenant);
        node.setOwnerId(801L); node.setName("scope.txt"); node.setPath("/scope.txt");
        node.setNodeType(1); return node;
    }

    @Test void mqDedupAndInsertUseEventTenantAcrossReusedThread() {
        SyncChangeLogMapper mapper = mock(SyncChangeLogMapper.class);
        List<Long> queryTenants = new ArrayList<>(), insertTenants = new ArrayList<>();
        when(mapper.selectCount(any())).thenAnswer(call -> { queryTenants.add(TenantContext.getTenantIdOrNull()); return 0L; });
        when(mapper.insert(any(SyncChangeLog.class))).thenAnswer(call -> {
            SyncChangeLog row = call.getArgument(0);
            assertEquals(row.getTenantId(), TenantContext.getTenantIdOrNull());
            insertTenants.add(row.getTenantId()); return 1;
        });
        SyncChangeMessageConsumer consumer = new SyncChangeMessageConsumer(mapper, mock(SyncPushService.class));
        for (long tenant : new long[]{71, 72}) {
            consumer.onMessage(EventMessage.fromSyncChange(node(tenant), SyncChangeEvent.ChangeType.CREATE, null, tenant));
            assertNull(TenantContext.getTenantIdOrNull()); assertNull(TenantContext.getTenantModeOrNull());
        }
        assertEquals(List.of(71L, 72L), queryTenants);
        assertEquals(queryTenants, insertTenants);
    }

    @Test void mqFailureAndAlreadyProcessedReturnRestoreExactPriorScope() {
        SyncChangeLogMapper mapper = mock(SyncChangeLogMapper.class);
        SyncChangeMessageConsumer consumer = new SyncChangeMessageConsumer(mapper, mock(SyncPushService.class));
        TenantContext.setTenantId(55L); TenantContext.setTenantMode("PRIVATE");
        when(mapper.selectCount(any())).thenThrow(new IllegalStateException("fixture"));
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(
                EventMessage.fromSyncChange(node(71), SyncChangeEvent.ChangeType.CREATE, null, 1L)));
        assertEquals(55L, TenantContext.getTenantIdOrNull()); assertEquals("PRIVATE", TenantContext.getTenantModeOrNull());
        doReturn(1L).when(mapper).selectCount(any());
        consumer.onMessage(EventMessage.fromSyncChange(node(72), SyncChangeEvent.ChangeType.CREATE, null, 2L));
        assertEquals(55L, TenantContext.getTenantIdOrNull()); verify(mapper, never()).insert(any(SyncChangeLog.class));
    }

    @Test void localAsyncInsertFailureDoesNotLeakScopeToNextEvent() {
        SyncChangeLogMapper mapper = mock(SyncChangeLogMapper.class);
        when(mapper.insert(any(SyncChangeLog.class))).thenAnswer(call -> {
            SyncChangeLog row = call.getArgument(0);
            assertEquals(row.getTenantId(), TenantContext.getTenantIdOrNull());
            if (row.getTenantId() == 71L) throw new IllegalStateException("fixture");
            return 1;
        });
        SyncChangeLogListener listener = new SyncChangeLogListener(mapper, mock(SyncPushService.class));
        listener.onSyncChange(new SyncChangeEvent(this, node(71), SyncChangeEvent.ChangeType.CREATE));
        assertNull(TenantContext.getTenantIdOrNull());
        listener.onSyncChange(new SyncChangeEvent(this, node(72), SyncChangeEvent.ChangeType.CREATE));
        assertNull(TenantContext.getTenantIdOrNull()); assertNull(TenantContext.getTenantModeOrNull());
        verify(mapper, times(2)).insert(any(SyncChangeLog.class));
    }
}
