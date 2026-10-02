package com.stcloud.admin;

import com.stcloud.admin.aspect.AuditAspect;
import com.stcloud.admin.entity.AuditLog;
import com.stcloud.admin.mapper.AuditLogMapper;
import com.stcloud.common.annotation.Auditable;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class AuditAspectTenantTest {
    private AuditAspect aspect;
    private AuditLogMapper mapper;
    private ExecutorService worker;
    private final List<Long> observed=Collections.synchronizedList(new ArrayList<>());
    @BeforeEach void setup(){
        TenantContext.clear();UserContext.clear();aspect=new AuditAspect();mapper=mock(AuditLogMapper.class);
        ((ExecutorService) ReflectionTestUtils.getField(aspect,"auditExecutor")).shutdownNow();
        worker=Executors.newSingleThreadExecutor();
        ReflectionTestUtils.setField(aspect,"auditExecutor",worker);ReflectionTestUtils.setField(aspect,"auditLogMapper",mapper);
        doAnswer(call->{AuditLog entry=call.getArgument(0);assertEquals(entry.getTenantId(),TenantContext.getTenantIdOrNull());observed.add(entry.getTenantId());return 1;}).when(mapper).insert(any(AuditLog.class));
    }
    @AfterEach void cleanup(){aspect.shutdown();TenantContext.clear();UserContext.clear();}
    public Object endpoint(){return "ok";}
    private void audit(Long tenant,String action) throws Throwable {
        if(tenant==null) UserContext.clear(); else UserContext.setCurrentUser(UserContext.CurrentUser.builder().userId(3L).tenantId(tenant).username("fixture").build());
        var join=mock(ProceedingJoinPoint.class);var signature=mock(MethodSignature.class);var annotation=mock(Auditable.class);
        when(join.getSignature()).thenReturn(signature);when(join.getArgs()).thenReturn(new Object[0]);when(join.proceed()).thenReturn("ok");
        when(signature.getParameterNames()).thenReturn(new String[0]);when(signature.getMethod()).thenReturn(getClass().getMethod("endpoint"));
        when(annotation.action()).thenReturn(action);when(annotation.targetType()).thenReturn("USER");
        assertEquals("ok",aspect.audit(join,annotation));
    }
    private void assertWorkerCleared() throws Exception {
        assertNull(worker.submit(TenantContext::getTenantIdOrNull).get(5,TimeUnit.SECONDS));
        assertNull(worker.submit(TenantContext::getTenantModeOrNull).get(5,TimeUnit.SECONDS));
    }
    @Test void twoTenantsRemainIsolatedOnReusedWriter() throws Throwable {
        audit(71L,"UPLOAD");audit(72L,"UPLOAD");assertWorkerCleared();assertEquals(List.of(71L,72L),observed);
    }
    @Test void anonymousLoginUsesExplicitDefaultTenant() throws Throwable {
        audit(null,"LOGIN");audit(null,"REGISTER");assertWorkerCleared();assertEquals(List.of(1L,1L),observed);
    }
    @Test void failedInsertDoesNotLeakTenantToNextAudit() throws Throwable {
        AtomicInteger calls=new AtomicInteger();
        doAnswer(call->{AuditLog entry=call.getArgument(0);assertEquals(entry.getTenantId(),TenantContext.getTenantIdOrNull());if(calls.getAndIncrement()==0)throw new IllegalStateException("fixture");observed.add(entry.getTenantId());return 1;}).when(mapper).insert(any(AuditLog.class));
        audit(71L,"UPLOAD");audit(72L,"UPLOAD");assertWorkerCleared();assertEquals(List.of(72L),observed);
    }
}
