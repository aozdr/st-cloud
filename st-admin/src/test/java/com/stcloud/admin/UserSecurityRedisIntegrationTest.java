package com.stcloud.admin;

import com.stcloud.admin.dto.AssignPermissionsRequest;
import com.stcloud.auth.dto.LoginResponse;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅显式提供隔离Redis端口时执行，禁止默认连接开发环境。 */
@EnabledIfSystemProperty(named = "test.redis.port", matches = "[0-9]+")
@SpringBootTest(classes = {UserManageSecurityIntegrationTest.App.class, UserSecurityRedisIntegrationTest.RedisApp.class}, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-real-redis;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.sql.init.schema-locations=classpath:security-fixture.sql",
        "stcloud.jwt.master-key=isolated-admin-redis-master-key-32-bytes"
})
class UserSecurityRedisIntegrationTest extends UserManageSecurityIntegrationTest {
    @Test void tc0116And21IndependentJvmRejectsRevokedSessionDespiteRedisFailure() throws Exception {
        var old = login(101,"original-pass");
        var server = org.h2.tools.Server.createTcpServer("-tcpPort","0","-tcpDaemon").start();
        var output = new java.util.concurrent.LinkedBlockingQueue<String>();
        var logs = new StringBuilder();
        Process child = null;
        var reader = Executors.newSingleThreadExecutor();
        try {
            String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
            child = new ProcessBuilder(javaExecutable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                    SecurityInstanceWorker.class.getName(),"jdbc:h2:tcp://127.0.0.1:"+server.getPort()+"/mem:admin-real-redis",
                    System.getProperty("test.redis.port")).redirectErrorStream(true).start();
            Process running = child;
            reader.submit(()-> {
                try (var stream = running.inputReader()) {
                    for (String line; (line=stream.readLine())!=null;) {
                        synchronized(logs) { logs.append(line).append('\n'); }
                        if (line.startsWith("PROBE ")) output.add(line);
                    }
                } catch (java.io.IOException failure) { output.add("PROBE IO_FAILURE"); }
            });
            assertEquals("PROBE READY",output.poll(30,TimeUnit.SECONDS));
            var input = child.outputWriter();
            input.write("INFLIGHT " + old.getToken() + "\n"); input.flush();
            assertEquals("PROBE AUTHORIZED true",output.poll(10,TimeUnit.SECONDS));
            // 主库提交后Redis清理抛错；保留F0是本用例必须成立的故障条件。
            doThrow(new org.springframework.data.redis.RedisConnectionFailureException("isolated revoke failure"))
                    .when(liveRedis).delete("stcloud:refresh:101");
            var request = new com.stcloud.admin.dto.UpdateUserRequest(); request.setResetPassword("new-password");
            assertThrows(RuntimeException.class,()->users.updateUser(101L,request));
            assertEquals(1,version(101));
            assertEquals(old.getRefreshToken(),liveRedis.opsForValue().get("stcloud:refresh:101"));
            input.write("RESUME\n"); input.flush();
            assertEquals("PROBE ACCESS true",output.poll(10,TimeUnit.SECONDS));
            input.write("ACCESS " + old.getToken() + "\n"); input.flush();
            assertEquals("PROBE ACCESS false",output.poll(10,TimeUnit.SECONDS));
            input.write("REFRESH " + old.getRefreshToken() + "\n"); input.flush();
            assertEquals("PROBE REFRESH false",output.poll(10,TimeUnit.SECONDS));
            input.write("STOP\n"); input.flush();
            assertTrue(child.waitFor(15,TimeUnit.SECONDS)); assertEquals(0,child.exitValue());
            synchronized(logs) { assertFalse(logs.toString().contains(old.getToken())); assertFalse(logs.toString().contains(old.getRefreshToken())); }
        } finally {
            if (child!=null && child.isAlive()) { child.destroyForcibly(); child.waitFor(5,TimeUnit.SECONDS); }
            reader.shutdownNow(); reader.awaitTermination(5,TimeUnit.SECONDS); server.stop();
        }
    }
    @Test void tcdb11SchemaExpansionRequiresOldSessionReLoginAndNewInstances() throws Exception {
        String legacyAccess=signed("access",null,101,1), legacyRefresh=signed("refresh",null,101,1);
        liveRedis.opsForValue().set("stcloud:refresh:101",legacyRefresh);
        var legacy=LoginResponse.builder().userId(101L).token(legacyAccess).refreshToken(legacyRefresh).build();
        // 旧鉴权行为对照仅验签/有效期，不查询security_version；模拟混部风险而非宣称运行了旧发行包。
        assertEquals(101L,((Number)jwt.parseToken(legacyAccess).get("userId")).longValue());
        assertFalse(current(legacy));
        assertThrows(RuntimeException.class,()->auth.refreshToken(legacyRefresh));
        var fresh=login(101,"original-pass");assertTrue(current(fresh));
        var server=org.h2.tools.Server.createTcpServer("-tcpPort","0","-tcpDaemon").start();
        var queue=new java.util.concurrent.LinkedBlockingQueue<String>();
        Process child=null;var reader=Executors.newSingleThreadExecutor();
        try {
            child=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),
                    "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                    SecurityInstanceWorker.class.getName(),"jdbc:h2:tcp://127.0.0.1:"+server.getPort()+"/mem:admin-real-redis",
                    System.getProperty("test.redis.port")).redirectErrorStream(true).start();
            Process running=child;
            reader.submit(()-> {try(var lines=running.inputReader()) {
                for(String line;(line=lines.readLine())!=null;) if(line.startsWith("PROBE ")) queue.add(line);
            } catch(java.io.IOException error) {queue.add("PROBE IO_FAILURE");}});
            assertEquals("PROBE READY",queue.poll(30,TimeUnit.SECONDS));
            var input=child.outputWriter();
            input.write("ACCESS "+legacyAccess+"\n");input.flush();
            assertEquals("PROBE ACCESS false",queue.poll(10,TimeUnit.SECONDS));
            liveRedis.opsForValue().set("stcloud:refresh:101",legacyRefresh);
            input.write("REFRESH "+legacyRefresh+"\n");input.flush();
            assertEquals("PROBE REFRESH false",queue.poll(10,TimeUnit.SECONDS));
            fresh=login(101,"original-pass");
            input.write("ACCESS "+fresh.getToken()+"\n");input.flush();
            assertEquals("PROBE ACCESS true",queue.poll(10,TimeUnit.SECONDS));
            input.write("REFRESH "+fresh.getRefreshToken()+"\n");input.flush();
            assertEquals("PROBE REFRESH true",queue.poll(10,TimeUnit.SECONDS));
            var request=new com.stcloud.admin.dto.UpdateUserRequest();request.setResetPassword("rollout-new-password");
            users.updateUser(101L,request);assertEquals(1,version(101));
            assertFalse(current(fresh));
            input.write("ACCESS "+fresh.getToken()+"\n");input.flush();
            assertEquals("PROBE ACCESS false",queue.poll(10,TimeUnit.SECONDS));
            // 若仍由旧的仅验签逻辑处理，这个已撤销令牌仍有效，故不能把DDL完成记为发布完成。
            assertNotNull(jwt.parseToken(fresh.getToken()));
            var relogin=login(101,"rollout-new-password");assertTrue(current(relogin));
            input.write("ACCESS "+relogin.getToken()+"\n");input.flush();
            assertEquals("PROBE ACCESS true",queue.poll(10,TimeUnit.SECONDS));
            input.write("STOP\n");input.flush();assertTrue(child.waitFor(15,TimeUnit.SECONDS));assertEquals(0,child.exitValue());
        } finally {
            if(child!=null&&child.isAlive()) {child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}
            reader.shutdownNow();reader.awaitTermination(5,TimeUnit.SECONDS);server.stop();
        }
    }

    @Configuration
    static class RedisApp {
        @Bean(destroyMethod = "destroy") LettuceConnectionFactory isolatedRedisConnection(@Value("${test.redis.port}") int port) {
            return new LettuceConnectionFactory("127.0.0.1", port);
        }
        @Bean @Primary StringRedisTemplate realRedis(LettuceConnectionFactory isolatedRedisConnection) {
            return new StringRedisTemplate(isolatedRedisConnection);
        }
    }
    @SpyBean(name = "realRedis") StringRedisTemplate liveRedis;
    @SpyBean SysUserMapper spyUsers;
    @SpyBean com.stcloud.auth.mapper.SysRoleMapper spyRoles;
    @org.springframework.beans.factory.annotation.Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @ParameterizedTest @ValueSource(strings={"register-commit","register-rollback","admin-commit","admin-rollback"})
    void tc0122NewAccountDoesNotEscapeTransaction(String mode) throws Exception {
        var observer = Executors.newSingleThreadExecutor();
        var captured = new java.util.concurrent.atomic.AtomicReference<LoginResponse>();
        String name = "commit-probe-" + java.util.UUID.randomUUID();
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
                LoginResponse issued;
                if (mode.startsWith("register")) {
                    var request = new com.stcloud.auth.dto.RegisterRequest(); request.setUsername(name); request.setPassword("original-pass");
                    issued = auth.register(request);
                } else {
                    var request = new com.stcloud.admin.dto.CreateUserRequest(); request.setUsername(name); request.setPassword("original-pass");
                    var created = users.createUser(request);
                    issued = LoginResponse.builder().userId(created.getId()).build();
                }
                captured.set(issued);
                assertNull(liveRedis.opsForValue().get("stcloud:refresh:" + issued.getUserId()));
                try {
                    assertFalse(observer.submit(() -> {
                        context(); try { return security.isCurrent(issued.getUserId(),1L,version(issued.getUserId())); }
                        catch (org.springframework.dao.EmptyResultDataAccessException absent) { return false; }
                        finally { cleanup(); }
                    }).get(10,TimeUnit.SECONDS));
                } catch (Exception error) { throw new AssertionError(error); }
                if (mode.endsWith("rollback")) status.setRollbackOnly();
            });
            var issued = captured.get();
            if (mode.endsWith("rollback")) {
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?",Integer.class,issued.getUserId()));
                assertNull(liveRedis.opsForValue().get("stcloud:refresh:" + issued.getUserId()));
                if (issued.getToken()!=null) revoked(issued);
            } else {
                assertTrue(version(issued.getUserId())>=0);
                if (mode.startsWith("register")) {
                    assertEquals(0,version(issued.getUserId()));
                    assertEquals(issued.getRefreshToken(),liveRedis.opsForValue().get("stcloud:refresh:" + issued.getUserId()));
                    assertTrue(current(issued));
                    assertTrue(current(auth.refreshToken(issued.getRefreshToken())));
                }
                var request = new com.stcloud.auth.dto.LoginRequest(); request.setUsername(name); request.setPassword("original-pass");
                var loggedIn = auth.login(request,"127.0.0.1");
                assertTrue(current(loggedIn)); assertFalse(loggedIn.getPermissions().contains("admin:user:manage"));
            }
        } finally { observer.shutdownNow(); assertTrue(observer.awaitTermination(5,TimeUnit.SECONDS)); }
    }

    boolean filtered(String token,String uri,String range) throws Exception {
        cleanup();
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET",uri);
        if(token!=null) request.addHeader("Authorization","Bearer " + token);
        if(range!=null) request.addHeader("Range",range);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        try {
            new com.stcloud.auth.security.JwtAuthenticationFilter(jwt,security,liveRedis).doFilter(request,response,
                    (req,res)->response.setStatus(com.stcloud.common.context.UserContext.getUserId()==null?401:200));
            assertNull(com.stcloud.common.context.UserContext.getCurrentUser());
            return response.getStatus()==200;
        } finally { context(); }
    }

    @ParameterizedTest @ValueSource(strings={"refresh","download","editor","unknown"})
    void tc0109SignedPurposeAndEndpointRestrictions(String type) throws Exception {
        String token;
        if(type.equals("download")) token=jwt.generateDownloadToken(101L,1L,"security-101",List.of("user"),List.of(),1,77L);
        else if(type.equals("editor")) token=jwt.generateEditorToken(101L,1L,"security-101",List.of("user"),List.of(),1,77L);
        else token=signed(type,0,101,1);
        assertFalse(filtered(token,"/api/test/protected",null));
        assertFalse(filtered(token,"/api/file/78/stream",null));
        if(type.equals("download") || type.equals("editor")) {
            assertTrue(filtered(token,"/api/file/77/stream",null));
            assertEquals(type.equals("editor"),filtered(token,"/api/file/77/stream",null));
            assertTrue(filtered(token,"/api/file/77/stream","bytes=100-"));
            var claims=new java.util.HashMap<String,Object>(jwt.parseToken(token));
            claims.remove("exp");
            String expired=io.jsonwebtoken.Jwts.builder().claims(claims).expiration(new java.util.Date(System.currentTimeMillis()-2000))
                    .signWith((javax.crypto.SecretKey)org.springframework.test.util.ReflectionTestUtils.getField(jwt,"signingKey")).compact();
            assertFalse(filtered(expired,"/api/file/77/stream","bytes=100-"));
        }
    }

    @Test void tc0110ExceptionCleanupSignatureAndExpiry() throws Exception {
        var first=login(101,"original-pass"); var second=login(102,"original-pass");
        cleanup();
        var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/test/protected");
        request.addHeader("Authorization","Bearer " + first.getToken());
        var filter=new com.stcloud.auth.security.JwtAuthenticationFilter(jwt,security,liveRedis);
        assertThrows(jakarta.servlet.ServletException.class,()->filter.doFilter(request,new org.springframework.mock.web.MockHttpServletResponse(),(req,res)-> {
            assertEquals(101L,com.stcloud.common.context.UserContext.getUserId());
            throw new jakarta.servlet.ServletException("isolated downstream failure");
        }));
        assertNull(com.stcloud.common.context.UserContext.getCurrentUser());
        assertNull(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
        context(); assertTrue(current(second)); assertFalse(filtered(null,"/api/test/protected",null));
        String[] parts=first.getToken().split("\\.");
        String bad=parts[0]+"."+parts[1]+"."+(parts[2].charAt(0)=='A'?'B':'A')+parts[2].substring(1);
        assertFalse(filtered(bad,"/api/test/protected",null));
        var claims=new java.util.HashMap<String,Object>(jwt.parseToken(first.getToken())); claims.remove("exp");
        String expired=io.jsonwebtoken.Jwts.builder().claims(claims).expiration(new java.util.Date(System.currentTimeMillis()-2000))
                .signWith((javax.crypto.SecretKey)org.springframework.test.util.ReflectionTestUtils.getField(jwt,"signingKey")).compact();
        assertFalse(filtered(expired,"/api/test/protected",null));
    }

    String signed(String type, Object version, long userId, long tenantId) {
        var claims = new java.util.HashMap<String, Object>();
        claims.put("type", type); claims.put("userId", userId); claims.put("tenantId", tenantId);
        if (version != null) claims.put("securityVersion", version);
        return io.jsonwebtoken.Jwts.builder().claims(claims).subject("security-" + userId)
                .expiration(new java.util.Date(System.currentTimeMillis() + 60000))
                .signWith((javax.crypto.SecretKey) org.springframework.test.util.ReflectionTestUtils.getField(jwt, "signingKey")).compact();
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "negative", "overflow", "decimal", "string"})
    void tc0107InvalidVersionRejectedForSignedAccessAndRefresh(String variant) {
        Object version = switch (variant) {
            case "negative" -> -1L; case "overflow" -> new java.math.BigInteger("9223372036854775808");
            case "decimal" -> 0.5; case "string" -> "0"; default -> null;
        };
        String access = signed("access", version, 101, 1), refresh = signed("refresh", version, 101, 1);
        liveRedis.opsForValue().set("stcloud:refresh:101", refresh);
        assertFalse(current(LoginResponse.builder().userId(101L).token(access).build()));
        assertThrows(BusinessException.class, () -> auth.refreshToken(refresh));
        assertTrue(current(login(101, "original-pass")));
    }

    @ParameterizedTest @ValueSource(strings = {"wrong-tenant", "missing-user", "disabled-user", "deleted-user", "disabled-tenant", "deleted-tenant"})
    void tc0106CurrentDatabaseStateRejectsSignedTokens(String variant) {
        var session = login(101, "original-pass");
        try {
            switch (variant) {
                case "wrong-tenant" -> session = LoginResponse.builder().userId(101L)
                        .token(signed("access",0,101,2)).refreshToken(signed("refresh",0,101,2)).build();
                case "missing-user" -> jdbc.update("DELETE FROM sys_user WHERE id=101");
                case "disabled-user" -> jdbc.update("UPDATE sys_user SET status=0 WHERE id=101");
                case "deleted-user" -> jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=101");
                case "disabled-tenant" -> jdbc.update("UPDATE sys_tenant SET status=0 WHERE id=1");
                case "deleted-tenant" -> jdbc.update("UPDATE sys_tenant SET deleted=1 WHERE id=1");
            }
            revoked(session);
        } finally { jdbc.update("UPDATE sys_tenant SET status=1,deleted=0 WHERE id=1"); }
    }

    void removePermission() {
        var request = new AssignPermissionsRequest(); request.setPermissionIds(List.of());
        roles.assignPermissions(101L, request);
    }

    @Test @SuppressWarnings("unchecked")
    void tc0111ActualRedisCasHasExactlyOneWinner() throws Exception {
        var old = login(101, "original-pass");
        assertEquals(old.getRefreshToken(), liveRedis.opsForValue().get("stcloud:refresh:101"));
        var barrier = new CyclicBarrier(2);
        // 两个真实刷新都到达Lua执行入口，随后在同一个Redis键上竞争。
        doAnswer(inv -> { barrier.await(10, TimeUnit.SECONDS); return inv.callRealMethod(); })
                .when(liveRedis).execute(any(RedisScript.class), anyList(), any(), any(), any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Object> refresh = () -> {
                context();
                try { return auth.refreshToken(old.getRefreshToken()); }
                catch (BusinessException rejected) { return rejected; }
                finally { cleanup(); }
            };
            var first = executor.submit(refresh); var second = executor.submit(refresh);
            List<Object> results = List.of(first.get(15,TimeUnit.SECONDS), second.get(15,TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(LoginResponse.class::isInstance).count());
            assertEquals(1, results.stream().filter(BusinessException.class::isInstance).count());
            LoginResponse winner = (LoginResponse) results.stream().filter(LoginResponse.class::isInstance).findFirst().orElseThrow();
            assertEquals(winner.getRefreshToken(), liveRedis.opsForValue().get("stcloud:refresh:101"));
            assertTrue(current(winner));
            assertThrows(BusinessException.class, () -> auth.refreshToken(old.getRefreshToken()));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS)); }
    }

    @ParameterizedTest @ValueSource(strings = {"first-check", "last-check", "revoke-before-cas"})
    void tc0112And13RevocationCannotBeInheritedByOldRefresh(String phase) throws Exception {
        var old = login(101,"original-pass");
        var reached = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var checks = new AtomicInteger();
        int pauseAfter = phase.equals("first-check") ? 1 : 2;
        doAnswer(inv -> {
            Object current = inv.callRealMethod();
            if (Thread.currentThread().getName().equals("refresh-race") && checks.incrementAndGet() == pauseAfter) {
                reached.countDown(); assertTrue(resume.await(10,TimeUnit.SECONDS));
            }
            return current;
        }).when(security).isCurrent(any(),any(),any());
        var executor = Executors.newSingleThreadExecutor(r -> new Thread(r,"refresh-race"));
        try {
            var future = executor.submit(() -> {
                context(); try { return (Object) auth.refreshToken(old.getRefreshToken()); }
                catch (BusinessException rejected) { return (Object) rejected; }
                finally { cleanup(); }
            });
            assertTrue(reached.await(10,TimeUnit.SECONDS));
            removePermission(); assertEquals(1,version(101));
            LoginResponse newSession = null;
            if (phase.equals("first-check")) newSession = login(101,"original-pass");
            if (phase.equals("revoke-before-cas")) auth.revokeRefreshToken(101L);
            resume.countDown(); Object result = future.get(15,TimeUnit.SECONDS);
            if (phase.equals("last-check")) {
                assertInstanceOf(LoginResponse.class,result);
                var stale = (LoginResponse) result;
                assertEquals(0,((Number)jwt.parseToken(stale.getToken()).get("securityVersion")).longValue());
                revoked(stale);
            } else {
                assertInstanceOf(BusinessException.class,result);
                assertEquals(newSession == null ? null : newSession.getRefreshToken(), liveRedis.opsForValue().get("stcloud:refresh:101"));
            }
        } finally { resume.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS)); }
    }

    @Test void tc0108PrimaryQueryFailureFailsClosedWithoutRedisFallback() {
        var old = login(101,"original-pass");
        clearInvocations(liveRedis);
        doThrow(new org.springframework.dao.QueryTimeoutException("injected primary timeout"))
                .when(spyUsers).selectSecurityState(1L,101L);
        assertFalse(current(old));
        verify(liveRedis, never()).opsForValue();
        reset(spyUsers);
        assertTrue(current(old));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void tc0114LoginSnapshotCannotInheritNewVersion(boolean afterRoleRead) throws Exception {
        var reached = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var actualRoles = sqlSession.getMapper(com.stcloud.auth.mapper.SysRoleMapper.class);
        doAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.Wrapper<com.stcloud.auth.entity.SysRole> query = inv.getArgument(0);
            if (!Thread.currentThread().getName().equals("login-race")) return actualRoles.selectList(query);
            Object result = afterRoleRead ? actualRoles.selectList(query) : null;
            reached.countDown(); assertTrue(resume.await(10, TimeUnit.SECONDS));
            return afterRoleRead ? result : actualRoles.selectList(query);
        }).when(spyRoles).selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        var executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "login-race"));
        try {
            var future = executor.submit(() -> {
                context(); try { return login(101, "original-pass"); } finally { cleanup(); }
            });
            assertTrue(reached.await(10, TimeUnit.SECONDS));
            removePermission(); assertEquals(1, version(101));
            resume.countDown();
            var stale = future.get(15, TimeUnit.SECONDS);
            // 登录使用初次读取的版本；并发撤权后无论权限读取落在哪侧，都不能继承新版本。
            assertEquals(0, ((Number) jwt.parseToken(stale.getToken()).get("securityVersion")).longValue());
            revoked(stale);
            var fresh = login(101, "original-pass");
            assertTrue(current(fresh));
            assertFalse(fresh.getPermissions().contains("admin:user:manage"));
        } finally { resume.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @ParameterizedTest @ValueSource(strings = {"assign-permissions", "permissions-assign", "assign-delete", "delete-assign"})
    void tc0115RoleAssignmentAndRevocationSerialize(String order) throws Exception {
        var oldMember = login(101, "original-pass");
        var oldCandidate = login(102, "original-pass");
        boolean assignFirst = order.startsWith("assign");
        boolean deleting = order.contains("delete");
        var held = new CountDownLatch(1); var attempted = new CountDownLatch(1); var resume = new CountDownLatch(1);
        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("role-second")) attempted.countDown();
            inv.callRealMethod();
            if (Thread.currentThread().getName().equals("role-first")) {
                held.countDown(); assertTrue(resume.await(10, TimeUnit.SECONDS));
            }
            return null;
        }).when(security).lockTenant(1L);
        java.util.concurrent.Callable<Object> assign = () -> {
            context(); try { roles.assignRolesToUser(102L, List.of("101")); return "ok"; }
            catch (BusinessException rejected) { return rejected; } finally { cleanup(); }
        };
        java.util.concurrent.Callable<Object> revoke = () -> {
            context(); try { if (deleting) roles.deleteRole(101L); else removePermission(); return "ok"; }
            finally { cleanup(); }
        };
        var firstPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "role-first"));
        var secondPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "role-second"));
        try {
            var first = firstPool.submit(assignFirst ? assign : revoke);
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var second = secondPool.submit(assignFirst ? revoke : assign);
            assertTrue(attempted.await(10, TimeUnit.SECONDS));
            assertFalse(second.isDone(), "第二个事务不能越过已持有的租户安全写锁");
            resume.countDown();
            assertEquals("ok", first.get(15, TimeUnit.SECONDS));
            Object result = second.get(15, TimeUnit.SECONDS);
            if (deleting && !assignFirst) assertInstanceOf(BusinessException.class, result);
            else assertEquals("ok", result);
            assertEquals(1, version(101)); assertEquals(1, version(103));
            assertEquals(assignFirst ? 2 : deleting ? 0 : 1, version(102));
            revoked(oldMember);
            if (assignFirst || !deleting) revoked(oldCandidate); else assertTrue(current(oldCandidate));
            assertEquals(deleting ? 0 : 3, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE role_id=101", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=101", Integer.class));
            var fresh = login(102, "original-pass");
            assertTrue(current(fresh)); assertFalse(fresh.getPermissions().contains("admin:user:manage"));
        } finally {
            resume.countDown(); firstPool.shutdownNow(); secondPool.shutdownNow();
            assertTrue(firstPool.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(secondPool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
