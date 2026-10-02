package com.stcloud.admin;

import com.stcloud.admin.service.UserRedisKeyCleanupService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserRedisKeyCleanupServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final UserRedisKeyCleanupService cleaner = new UserRedisKeyCleanupService(redis);

    @SuppressWarnings("unchecked")
    private Cursor<String> cursor(List<String> keys) {
        Cursor<String> cursor = mock(Cursor.class);
        Iterator<String> iterator = keys.iterator();
        when(cursor.hasNext()).thenAnswer(inv -> iterator.hasNext());
        when(cursor.next()).thenAnswer(inv -> iterator.next());
        return cursor;
    }

    @Test void onlyFixedUserSegmentsAreDeletedWithoutReadingContents() {
        var active = cursor(List.of("team:active:5:101", "team:active:6:101", "team:active:5:1010",
                "team:active:101:102", "team:active:prefix:101", "team:active:5:101:extra"));
        var cache = cursor(List.of("stcloud:cache:5:7:101:view", "stcloud:cache:6:0:101:",
                "stcloud:cache:5:7:1010:view", "stcloud:cache:5:101:102:view",
                "stcloud:cache:101:7:102:view", "stcloud:cache:sys_config:7:101:view",
                "stcloud:cache:5:7:101:view:extra", "editor:active:101", "stcloud:download:used:101"));
        when(redis.scan(any(ScanOptions.class))).thenReturn(active, cache);
        Set<String> deleted = new LinkedHashSet<>();
        when(redis.delete(anyCollection())).thenAnswer(inv -> {
            deleted.addAll(inv.getArgument(0)); return 1L;
        });

        cleaner.clearUserOwnedKeys(101L);
        assertEquals(Set.of("team:active:5:101", "team:active:6:101",
                "stcloud:cache:5:7:101:view", "stcloud:cache:6:0:101:"), deleted);
        verify(active).close(); verify(cache).close();
        verify(redis, times(2)).scan(any(ScanOptions.class));
        verify(redis, times(2)).delete(anyCollection());
        // verifyNoMoreInteractions 排除 GET、集合查询、KEYS 等内容读取及全库操作。
        verifyNoMoreInteractions(redis);
    }

    @Test void scanResultsAreDeletedInBoundedBatchesIncludingDuplicates() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 300; i++) keys.add("team:active:" + i + ":101");
        keys.add(keys.get(0));
        var active = cursor(keys); var empty = cursor(List.of());
        when(redis.scan(any(ScanOptions.class))).thenReturn(active, empty);
        List<Integer> sizes = new ArrayList<>();
        Set<String> deleted = new LinkedHashSet<>();
        when(redis.delete(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> batch = inv.getArgument(0);
            sizes.add(batch.size()); deleted.addAll(batch); return (long) batch.size();
        });
        cleaner.clearUserOwnedKeys(101L);
        assertEquals(List.of(128,128,45), sizes);
        assertEquals(300, deleted.size());
        verify(active).close(); verify(empty).close();
    }

    @Test void invalidUserIdsDoNothing() {
        cleaner.clearUserOwnedKeys(null); cleaner.clearUserOwnedKeys(0L); cleaner.clearUserOwnedKeys(-1L);
        verifyNoInteractions(redis);
    }

    @Test void deleteFailurePropagatesAndClosesTheScanCursor() {
        var active = cursor(List.of("team:active:5:101"));
        when(redis.scan(any(ScanOptions.class))).thenReturn(active);
        when(redis.delete(anyCollection())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("isolated failure"));
        assertThrows(org.springframework.data.redis.RedisConnectionFailureException.class, () -> cleaner.clearUserOwnedKeys(101L));
        verify(active).close();
    }
}
