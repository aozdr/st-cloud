package com.stcloud.team.util;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stcloud.auth.entity.SysUser;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.team.entity.TeamActivity;
import com.stcloud.team.mapper.TeamActivityMapper;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TeamActivityTenantTest {
    private TeamActivityHelper helper;
    private TeamActivityMapper activities;
    private SysUserMapper users;
    private ExecutorService worker;
    private final List<TeamActivity> written = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach void setup() {
        TenantContext.clear(); UserContext.clear();
        helper = new TeamActivityHelper();
        ((ExecutorService) ReflectionTestUtils.getField(helper, "executor")).shutdownNow();
        worker = Executors.newSingleThreadExecutor();
        activities = mock(TeamActivityMapper.class); users = mock(SysUserMapper.class);
        ReflectionTestUtils.setField(helper, "executor", worker);
        ReflectionTestUtils.setField(helper, "teamActivityMapper", activities);
        ReflectionTestUtils.setField(helper, "sysUserMapper", users);
        when(users.selectById(3L)).thenAnswer(call -> {
            SysUser user = new SysUser();
            user.setUsername("tenant-" + TenantContext.getTenantIdOrNull());
            return user;
        });
        doAnswer(call -> {
            TeamActivity activity = call.getArgument(0);
            assertEquals(activity.getTenantId(), TenantContext.getTenantIdOrNull());
            written.add(activity); return 1;
        }).when(activities).insert(any(TeamActivity.class));
    }

    @AfterEach void cleanup() { helper.shutdown(); TenantContext.clear(); UserContext.clear(); }

    private void log(long tenant) {
        TenantContext.setTenantId(tenant); TenantContext.setTenantMode("SAAS");
        UserContext.setCurrentUser(UserContext.CurrentUser.builder().userId(3L).tenantId(tenant).build());
        helper.log(4L, "MEMBER_JOIN", "MEMBER", 3L, "fixture");
    }

    private void assertWorkerCleared() throws Exception {
        assertNull(worker.submit(TenantContext::getTenantIdOrNull).get(5, TimeUnit.SECONDS));
        assertNull(worker.submit(TenantContext::getTenantModeOrNull).get(5, TimeUnit.SECONDS));
    }

    @Test void reusedThreadQueriesAndWritesTheCapturedTenant() throws Exception {
        log(71L); log(72L); assertWorkerCleared();
        assertEquals(List.of(71L, 72L), written.stream().map(TeamActivity::getTenantId).toList());
        assertEquals(List.of("tenant-71", "tenant-72"), written.stream().map(TeamActivity::getUsername).toList());
        assertEquals(72L, TenantContext.getTenantIdOrNull());
    }

    @Test void lookupFailureRestoresContextForNextRequest() throws Exception {
        when(users.selectById(3L)).thenAnswer(call -> {
            if (TenantContext.getTenantIdOrNull().equals(71L)) throw new IllegalStateException("fixture");
            return new SysUser();
        });
        log(71L); log(72L); assertWorkerCleared();
        assertEquals(List.of(72L), written.stream().map(TeamActivity::getTenantId).toList());
    }

    @Test void workerPreviousIdAndModeArePreciselyRestored() throws Exception {
        worker.submit(() -> { TenantContext.setTenantId(91L); TenantContext.setTenantMode("PRIVATE"); }).get();
        log(71L);
        assertEquals(91L, worker.submit(TenantContext::getTenantIdOrNull).get(5, TimeUnit.SECONDS));
        assertEquals("PRIVATE", worker.submit(TenantContext::getTenantModeOrNull).get(5, TimeUnit.SECONDS));
        assertEquals(71L, written.get(0).getTenantId());
        worker.submit(TenantContext::clear).get();
    }

    @Test void missingRequestTenantStillEmitsTheOriginalDiagnostic() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(TenantContext.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>(); appender.start(); logger.addAppender(appender);
        try {
            helper.log(4L, "UPDATE", "SPACE", 4L, "fixture"); assertWorkerCleared();
            assertEquals(1L, written.get(0).getTenantId());
            assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("租户上下文未设置")));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
