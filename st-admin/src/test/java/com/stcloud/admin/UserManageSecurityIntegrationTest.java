package com.stcloud.admin;

import com.stcloud.admin.dto.AssignPermissionsRequest;
import com.stcloud.admin.dto.CreateRoleRequest;
import com.stcloud.admin.dto.UpdateUserRequest;
import com.stcloud.admin.service.RoleService;
import com.stcloud.admin.service.UserManageService;
import com.stcloud.admin.service.impl.RoleServiceImpl;
import com.stcloud.admin.service.impl.UserManageServiceImpl;
import com.stcloud.auth.dto.LoginRequest;
import com.stcloud.auth.dto.LoginResponse;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.auth.service.AuthService;
import com.stcloud.auth.service.UserSecurityService;
import com.stcloud.auth.security.JwtAuthenticationFilter;
import com.stcloud.common.config.MyBatisPlusConfig;
import com.stcloud.common.config.MyMetaObjectHandler;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.utils.JwtUtils;
import com.stcloud.core.service.CloudStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实管理写事务、主库版本与签名令牌；Redis替身不代表Redis并发验收。 */
@SpringBootTest(classes = UserManageSecurityIntegrationTest.App.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-security;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.sql.init.schema-locations=classpath:security-fixture.sql",
        "stcloud.jwt.master-key=isolated-admin-test-master-key-32-bytes"
})
@ActiveProfiles("test")
class UserManageSecurityIntegrationTest {
    @Configuration
    @EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class,
            SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class, WebMvcAutoConfiguration.class})
    @MapperScan({"com.stcloud.auth.mapper", "com.stcloud.common.mapper"})
    @Import({MyBatisPlusConfig.class, MyMetaObjectHandler.class, UserManageServiceImpl.class,
            RoleServiceImpl.class, UserSecurityService.class, AuthService.class, JwtUtils.class})
    static class App {
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
        @Bean CloudStorageService cloudStorageService() { return mock(CloudStorageService.class); }
        @Bean @SuppressWarnings("unchecked") StringRedisTemplate redis() {
            var values = new ConcurrentHashMap<String, String>();
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(ops);
            when(ops.get(anyString())).thenAnswer(inv -> values.get(inv.getArgument(0)));
            doAnswer(inv -> { values.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                    .when(ops).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
            when(redis.delete(anyString())).thenAnswer(inv -> values.remove(inv.getArgument(0)) != null);
            return redis;
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired UserManageService users;
    @Autowired RoleService roles;
    @Autowired AuthService auth;
    @Autowired JwtUtils jwt;
    @Autowired PasswordEncoder passwords;
    @Autowired SysUserMapper userMapper;
    @Autowired StringRedisTemplate redis;
    @SpyBean UserSecurityService security;

    void context() {
        TenantContext.setTenantMode("SAAS");
        TenantContext.setTenantId(1L);
        UserContext.setCurrentUser(UserContext.CurrentUser.builder().userId(1L).tenantId(1L)
                .permissions(Set.of("admin:user:manage")).build());
    }

    @BeforeEach void prepare() {
        context();
        // 清理范围仅为本独立H2库的固定夹具ID，与任何开发/生产连接无关。
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (101,102,103)");
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id=101");
        jdbc.update("DELETE FROM sys_user WHERE id IN (101,102,103)");
        jdbc.update("DELETE FROM sys_role WHERE id=101");
        jdbc.update("INSERT INTO sys_role(id,tenant_id,role_code,role_name,status,built_in,data_scope) VALUES(101,1,'custom-admin','测试角色',1,0,3)");
        jdbc.update("INSERT INTO sys_role_permission(tenant_id,role_id,permission_id) VALUES(1,101,5)");
        String hash = passwords.encode("original-pass");
        for (long id : new long[]{101,102,103}) {
            jdbc.update("INSERT INTO sys_user(id,tenant_id,username,password,nickname,status,security_version,storage_quota) VALUES(?,1,?,?,'before',1,0,1000)", id, "security-" + id, hash);
            jdbc.update("INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(1,?,?)", id, id == 102 ? 2 : 101);
        }
    }
    @AfterEach void cleanup() { UserContext.clear(); TenantContext.clear(); }
    LoginResponse login(long id, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername("security-" + id); request.setPassword(password);
        return auth.login(request, "127.0.0.1");
    }
    long version(long id) { return jdbc.queryForObject("SELECT security_version FROM sys_user WHERE id=?", Long.class, id); }
    boolean current(LoginResponse token) {
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/test/protected");
        request.addHeader("Authorization", "Bearer " + token.getToken());
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        cleanup();
        try {
            new JwtAuthenticationFilter(jwt, security, redis).doFilter(request, response, (req, res) -> {
                // 受保护业务探针只接受过滤器建立的正确用户身份，不能只检查JWT签名。
                response.setStatus(token.getUserId().equals(UserContext.getUserId()) ? 200 : 401);
            });
            assertNull(UserContext.getCurrentUser());
            assertNull(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
            return response.getStatus() == 200;
        } catch (Exception exception) { throw new AssertionError(exception); }
        finally { context(); }
    }
    void revoked(LoginResponse response) {
        assertFalse(current(response));
        assertThrows(RuntimeException.class, () -> auth.refreshToken(response.getRefreshToken()));
    }

    @ParameterizedTest @ValueSource(strings = {"nickname", "quota"})
    void tc0104NicknameAndQuotaKeepSession(String field) {
        var old = login(101, "original-pass");
        var request = new UpdateUserRequest();
        if (field.equals("nickname")) request.setNickname("after"); else request.setStorageQuota(2000L);
        users.updateUser(101L, request);
        assertEquals(0, version(101)); assertTrue(current(old));
        assertEquals(old.getRefreshToken(), redis.opsForValue().get("stcloud:refresh:101"));
        assertEquals(0, version(103));
        assertEquals(field.equals("nickname") ? "after" : "before", userMapper.selectById(101L).getNickname());
        assertEquals(field.equals("quota") ? 2000L : 1000L, userMapper.selectById(101L).getStorageQuota());
    }

    @ParameterizedTest @ValueSource(strings = {"password", "disable", "delete"})
    void tc0101SecurityWritesRevokeAfterCommit(String operation) {
        var old = login(101, "original-pass"); var unaffected = login(102, "original-pass");
        var request = new UpdateUserRequest();
        if (operation.equals("delete")) users.deleteUser(101L);
        else { if (operation.equals("password")) request.setResetPassword("new-password"); else request.setStatus(0); users.updateUser(101L, request); }
        assertEquals(1, version(101)); revoked(old); assertTrue(current(unaffected)); assertEquals(0, version(102));
        if (operation.equals("password")) {
            assertThrows(RuntimeException.class, () -> login(101, "original-pass"));
            assertTrue(current(login(101, "new-password")));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"remove", "ordinary", "delete"})
    void tc0102RoleChangeRevokesOnlyMembers(String operation) {
        if (!operation.equals("delete")) roles.assignRolesToUser(101L, List.of("1"));
        var old = login(101, "original-pass"); var other = login(102, "original-pass");
        if (operation.equals("delete")) roles.deleteRole(101L);
        else roles.assignRolesToUser(101L, operation.equals("remove") ? List.of() : List.of("2"));
        revoked(old); assertTrue(current(other)); assertEquals(0, version(102));
        assertFalse(jwt.parseToken(login(101, "original-pass").getToken()).get("permissions", List.class).contains("admin:user:manage"));
    }

    @ParameterizedTest @ValueSource(strings = {"permission", "disable", "scope"})
    void tc0103AllRoleMembersIncludingOfflineAreRevoked(String operation) {
        var old = login(101, "original-pass"); var other = login(102, "original-pass");
        if (operation.equals("permission")) { var request = new AssignPermissionsRequest(); request.setPermissionIds(List.of()); roles.assignPermissions(101L, request); }
        else { var request = new CreateRoleRequest(); request.setRoleName("changed"); request.setStatus(operation.equals("disable") ? 0 : 1); request.setDataScope(1); roles.updateRole(101L, request); }
        assertEquals(1, version(101)); assertEquals(1, version(103)); assertEquals(0, version(102));
        revoked(old); assertFalse(security.isCurrent(103L,1L,0L)); assertTrue(current(other));
        var fresh = jwt.parseToken(login(101, "original-pass").getToken());
        if (operation.equals("scope")) assertEquals(1, ((Number) fresh.get("dataScope")).intValue());
        else assertFalse(fresh.get("permissions", List.class).contains("admin:user:manage"));
    }

    @ParameterizedTest @ValueSource(strings = {"password", "roles", "permissions"})
    void tc0105DatabaseFailureRollsBackFieldsAndVersion(String operation) {
        var old = login(101, "original-pass");
        // 安全版本SQL执行后触发真实数据库唯一键异常，证明业务字段及版本同事务回滚。
        if (operation.equals("permissions")) doAnswer(inv -> { inv.callRealMethod(); return jdbc.update("INSERT INTO sys_user_role(tenant_id,user_id,role_id) VALUES(1,101,101)"); }).when(security).incrementRoleMembers(1L,101L);
        else doAnswer(inv -> { inv.callRealMethod(); return jdbc.update("INSERT INTO sys_role_permission(tenant_id,role_id,permission_id) VALUES(1,101,5)"); }).when(security).increment(1L,101L);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> {
            if (operation.equals("password")) { var request = new UpdateUserRequest(); request.setResetPassword("never-committed"); users.updateUser(101L,request); }
            else if (operation.equals("roles")) roles.assignRolesToUser(101L,List.of("2"));
            else { var request = new AssignPermissionsRequest(); request.setPermissionIds(List.of()); roles.assignPermissions(101L,request); }
        });
        assertEquals(0, version(101)); assertTrue(current(old));
        assertTrue(passwords.matches("original-pass",userMapper.selectById(101L).getPassword()));
        assertEquals(old.getRefreshToken(), redis.opsForValue().get("stcloud:refresh:101"));
        assertEquals(0, version(103));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=101 AND role_id=101", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=101 AND permission_id=5", Integer.class));
    }

    @ParameterizedTest @ValueSource(strings = {"password", "disable-nickname", "disable-enable"})
    void tc0117And18StaleReadCannotOverwriteCommittedSecurityChange(String operation) throws Exception {
        var old = login(101,"original-pass");
        var read = new CountDownLatch(1); var resume = new CountDownLatch(1);
        // 管理A已经读取旧实体、尚未取安全写锁；允许B完整提交后再放行A。
        doAnswer(inv -> { if (Thread.currentThread().getName().equals("security-manager-A")) { read.countDown(); assertTrue(resume.await(10,TimeUnit.SECONDS)); } return inv.callRealMethod(); }).when(security).lockTenant(1L);
        var executor = Executors.newSingleThreadExecutor(r -> new Thread(r,"security-manager-A"));
        try {
            var future = executor.submit(() -> { context(); try { var request = new UpdateUserRequest(); request.setNickname("after-race"); if (operation.equals("disable-enable")) request.setStatus(1); users.updateUser(101L,request); } finally { cleanup(); } });
            assertTrue(read.await(10,TimeUnit.SECONDS));
            var request = new UpdateUserRequest(); if (operation.equals("password")) request.setResetPassword("new-password"); else request.setStatus(0);
            users.updateUser(101L,request); assertEquals(1,version(101));
            resume.countDown(); future.get(10,TimeUnit.SECONDS);
            assertEquals("after-race",userMapper.selectById(101L).getNickname()); revoked(old);
            if (operation.equals("password")) {
                assertEquals(1,version(101)); assertTrue(current(login(101,"new-password")));
                assertThrows(RuntimeException.class, () -> login(101,"original-pass"));
            } else {
                assertEquals(operation.equals("disable-enable") ? 1 : 0,userMapper.selectById(101L).getStatus());
                assertEquals(operation.equals("disable-enable") ? 2 : 1,version(101));
                assertFalse(security.isCurrent(101L,1L,0L)); assertFalse(security.isCurrent(101L,1L,1L));
            }
        } finally { resume.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS)); }
    }
}
