package com.stcloud.auth;

import cn.hutool.crypto.digest.BCrypt;
import com.stcloud.auth.entity.SysUser;
import com.stcloud.auth.mapper.SysPermissionMapper;
import com.stcloud.auth.mapper.SysRoleMapper;
import com.stcloud.auth.mapper.SysRolePermissionMapper;
import com.stcloud.auth.mapper.SysTenantMapper;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.auth.mapper.SysUserRoleMapper;
import com.stcloud.auth.service.AuthService;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * st-auth 集成测试基类。
 * <p>
 * 使用 H2 内存库 + 真实 MyBatis-Plus（含租户拦截器、自动填充）+ 真实 JwtUtils（BCrypt/签名密钥落库），
 * 验证登录/注册/刷新/当前用户主路径的密码校验与 Token 生成校验。
 * {@code @Transactional} 保证每个测试方法执行后自动回滚。
 */
@SpringBootTest(classes = AuthTestApplication.class)
@ActiveProfiles("test")
@Transactional
public abstract class AbstractAuthIntegrationTest {

    @Autowired
    protected AuthService authService;

    @Autowired
    protected JwtUtils jwtUtils;

    @Autowired
    protected SysUserMapper userMapper;

    @Autowired
    protected SysTenantMapper tenantMapper;

    @Autowired
    protected SysRoleMapper roleMapper;

    @Autowired
    protected SysPermissionMapper permissionMapper;

    @Autowired
    protected SysUserRoleMapper userRoleMapper;

    @Autowired
    protected SysRolePermissionMapper rolePermissionMapper;

    @Autowired
    protected ValueOperations<String, String> redisValueOperations;

    @BeforeEach
    void setUpTenantContext() {
        // 测试数据统一落在默认租户 1（与 docker init 种子数据一致）
        TenantContext.setTenantId(1L);
        TenantContext.setTenantMode("SAAS");
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
        TenantContext.clear();
    }

    /**
     * 插入测试用户（密码走真实 BCrypt 加密，tenant_id 由 MetaObjectHandler 自动填充）。
     */
    protected SysUser insertUser(String username, String password, Integer status) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(BCrypt.hashpw(password));
        user.setNickname("测试-" + username);
        user.setStatus(status != null ? status : 1);
        user.setStorageUsed(0L);
        user.setStorageQuota(10L * 1024 * 1024 * 1024);
        userMapper.insert(user);
        return user;
    }
}
