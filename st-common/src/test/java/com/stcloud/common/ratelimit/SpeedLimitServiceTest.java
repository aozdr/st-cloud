package com.stcloud.common.ratelimit;

import com.stcloud.common.context.UserContext;
import com.stcloud.common.entity.SysRateLimit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SpeedLimitService 解析一致性（R-2）：规则与版本必须来自同一份缓存快照。 */
@DisplayName("SpeedLimitService 规则快照")
class SpeedLimitServiceTest {

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("resolve 只读一次快照，并按最严格规则合并上传/下载")
    void resolveUsesSingleSnapshotAndMostRestrictiveRule() {
        SpeedLimitCache cache = mock(SpeedLimitCache.class);
        when(cache.snapshot(1001L, List.of("user")))
                .thenReturn(new SpeedLimitCache.LimitSnapshot(
                        userRule(2048, 4096), List.of(roleRule(1024, 0)), 13L));
        UserContext.setCurrentUser(UserContext.CurrentUser.builder()
                .userId(1001L).tenantId(1L).roles(List.of("user")).build());

        SpeedLimitResult result = new SpeedLimitService(cache).resolve();

        assertEquals(1024, result.getUploadSpeedLimit(), "用户 2048 与角色 1024 取更严格值");
        assertEquals(4096, result.getDownloadSpeedLimit(), "角色 0 表示不限速，不参与取最小值");
        assertEquals(13L, result.getVersion(), "版本必须与规则来自同一份快照");
        verify(cache, times(1)).snapshot(1001L, List.of("user"));
        verify(cache, never()).getUserRule(anyLong());
        verify(cache, never()).getRoleRules(any());
        verify(cache, never()).getVersion();
    }

    @Test
    @DisplayName("无登录上下文时返回不限速，不访问缓存")
    void anonymousIsUnlimited() {
        SpeedLimitCache cache = mock(SpeedLimitCache.class);

        SpeedLimitResult result = new SpeedLimitService(cache).resolve();

        assertEquals(0, result.getUploadSpeedLimit());
        assertEquals(0, result.getDownloadSpeedLimit());
        verify(cache, never()).snapshot(anyLong(), anyList());
    }

    private static SysRateLimit userRule(int upload, int download) {
        SysRateLimit rule = new SysRateLimit();
        rule.setScope(0);
        rule.setTargetId(1001L);
        rule.setUploadSpeedLimit(upload);
        rule.setDownloadSpeedLimit(download);
        return rule;
    }

    private static SysRateLimit roleRule(int upload, int download) {
        SysRateLimit rule = new SysRateLimit();
        rule.setScope(1);
        rule.setTargetCode("user");
        rule.setUploadSpeedLimit(upload);
        rule.setDownloadSpeedLimit(download);
        return rule;
    }
}
