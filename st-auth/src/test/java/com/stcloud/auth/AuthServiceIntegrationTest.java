package com.stcloud.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.auth.dto.LoginRequest;
import com.stcloud.auth.dto.LoginResponse;
import com.stcloud.auth.dto.RegisterRequest;
import com.stcloud.auth.entity.SysRole;
import com.stcloud.auth.entity.SysUser;
import com.stcloud.auth.entity.SysUserRole;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * st-auth 登录/认证主路径集成测试：
 * 注册（默认租户 + 默认角色）、登录（BCrypt 密码校验）、Token 生成/校验、
 * 刷新 Token（Redis 存储校验）、当前用户信息（角色权限加载）。
 */
@DisplayName("st-auth 认证主路径集成测试")
class AuthServiceIntegrationTest extends AbstractAuthIntegrationTest {

    @Test
    @DisplayName("注册：创建用户、分配默认 user 角色并签发可校验 Token")
    void register_createsUserAssignsDefaultRoleAndIssuesValidToken() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("pass123456");
        request.setNickname("新用户");
        request.setEmail("newuser@example.com");

        LoginResponse resp = authService.register(request);

        assertNotNull(resp.getUserId());
        assertNotNull(resp.getToken());
        assertNotNull(resp.getRefreshToken());
        assertEquals("newuser", resp.getUsername());

        // 用户真实落库
        SysUser saved = userMapper.selectById(resp.getUserId());
        assertNotNull(saved);
        assertEquals("新用户", saved.getNickname());
        assertEquals(1, saved.getStatus());

        // 默认 user 角色已分配
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, resp.getUserId()));
        assertEquals(1, userRoles.size());
        SysRole defaultRole = roleMapper.selectById(userRoles.get(0).getRoleId());
        assertEquals("user", defaultRole.getRoleCode());

        // Token 可真实校验，且携带角色与权限
        assertTrue(jwtUtils.validateToken(resp.getToken()));
        Claims claims = jwtUtils.parseToken(resp.getToken());
        assertEquals("newuser", claims.getSubject());
        assertEquals(1L, claims.get("tenantId", Long.class));
        assertTrue(claims.get("roles", List.class).contains("user"));
        assertFalse(claims.get("permissions", List.class).isEmpty());
    }

    @Test
    @DisplayName("登录成功：BCrypt 密码校验通过并签发可校验 Token")
    void login_success_verifiesPasswordAndIssuesValidToken() {
        SysUser user = insertUser("alice", "secret123", 1);
        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("secret123");

        LoginResponse resp = authService.login(request, "127.0.0.1");

        assertNotNull(resp.getToken());
        assertTrue(jwtUtils.validateToken(resp.getToken()));
        assertEquals(user.getId(), resp.getUserId());
        assertEquals("alice", resp.getUsername());

        // lastLoginAt / lastLoginIp 已更新
        SysUser updated = userMapper.selectById(user.getId());
        assertNotNull(updated.getLastLoginAt());
        assertEquals("127.0.0.1", updated.getLastLoginIp());
    }

    @Test
    @DisplayName("登录失败：密码错误抛出 PASSWORD_INCORRECT")
    void login_wrongPassword_rejected() {
        insertUser("bob", "correct-pass", 1);
        LoginRequest request = new LoginRequest();
        request.setUsername("bob");
        request.setPassword("wrong-pass");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(request, "127.0.0.1"));
        assertEquals(ResultCode.PASSWORD_INCORRECT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("登录失败：用户不存在抛出 USER_NOT_FOUND")
    void login_unknownUser_rejected() {
        LoginRequest request = new LoginRequest();
        request.setUsername("ghost");
        request.setPassword("whatever1");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(request, "127.0.0.1"));
        assertEquals(ResultCode.USER_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("登录失败：账号被禁用拒绝登录")
    void login_disabledUser_rejected() {
        insertUser("banned", "secret123", 0);
        LoginRequest request = new LoginRequest();
        request.setUsername("banned");
        request.setPassword("secret123");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(request, "127.0.0.1"));
        assertTrue(ex.getMessage().contains("禁用"));
    }

    @Test
    @DisplayName("刷新 Token：Redis 命中存储的 refresh token 时轮换签发新 Token")
    void refreshToken_rotatesWhenStored() throws InterruptedException {
        insertUser("carol", "secret123", 1);
        LoginRequest request = new LoginRequest();
        request.setUsername("carol");
        request.setPassword("secret123");
        LoginResponse loginResp = authService.login(request, "127.0.0.1");
        assertNotNull(loginResp.getRefreshToken());

        // JWT 的 iat 精度为秒：同一秒内相同 claims 签发的令牌字节相同，
        // 等待 1.1s 使刷新后的新令牌必然携带新 iat，从而真实验证"轮换签发"。
        Thread.sleep(1100);

        // 模拟 Redis 命中：登录时写入的 refresh token 仍然有效
        when(redisValueOperations.get("stcloud:refresh:" + loginResp.getUserId()))
                .thenReturn(loginResp.getRefreshToken());

        LoginResponse resp = authService.refreshToken(loginResp.getRefreshToken());

        assertNotNull(resp.getToken());
        assertTrue(jwtUtils.validateToken(resp.getToken()));
        // 访问令牌已轮换（新 iat）
        assertNotEquals(loginResp.getToken(), resp.getToken());
        assertNotNull(resp.getRefreshToken());
        assertTrue(jwtUtils.validateToken(resp.getRefreshToken()));
    }

    @Test
    @DisplayName("刷新 Token：Redis 无记录（已吊销）时抛出 TOKEN_INVALID")
    void refreshToken_invalidWhenNotCached() {
        insertUser("dave", "secret123", 1);
        LoginRequest request = new LoginRequest();
        request.setUsername("dave");
        request.setPassword("secret123");
        LoginResponse loginResp = authService.login(request, "127.0.0.1");

        // 模拟 Redis 未命中：refresh token 已过期/吊销
        when(redisValueOperations.get(ArgumentMatchers.anyString())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.refreshToken(loginResp.getRefreshToken()));
        assertEquals(ResultCode.TOKEN_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("当前用户信息：从 DB 加载角色与权限（admin 全量权限）")
    void getCurrentUserInfo_returnsRolesAndPermissionsFromDb() {
        UserContext.setCurrentUser(UserContext.CurrentUser.builder()
                .userId(1L)
                .tenantId(1L)
                .username("admin")
                .roles(List.of("admin"))
                .permissions(Set.of("admin:user:manage"))
                .dataScope(3)
                .build());

        LoginResponse resp = authService.getCurrentUserInfo();

        assertEquals("admin", resp.getUsername());
        assertTrue(resp.getRoles().contains("admin"));
        assertTrue(resp.getPermissions().contains("admin:user:manage"));
        assertTrue(resp.getPermissions().contains("transfer:speed:limit"));
    }
}
