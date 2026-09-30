package com.stcloud.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stcloud.admin.dto.CreateUserRequest;
import com.stcloud.admin.dto.UpdateUserRequest;
import com.stcloud.admin.dto.UserManageVO;
import com.stcloud.admin.service.RoleService;
import com.stcloud.admin.service.UserManageService;
import com.stcloud.auth.service.AuthService;
import com.stcloud.auth.service.UserSecurityService;
import com.stcloud.auth.entity.SysRole;
import com.stcloud.auth.entity.SysUser;
import com.stcloud.auth.enums.UserStatus;
import com.stcloud.auth.mapper.SysRoleMapper;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Slf4j
@Service
public class UserManageServiceImpl implements UserManageService {

    private static final Long DEFAULT_QUOTA = 10L * 1024 * 1024 * 1024; // 10GB

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private SysRoleMapper sysRoleMapper;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private RoleService roleService;
    @Resource
    private AuthService authService;
    @Resource
    private UserSecurityService userSecurityService;
    @Resource
    private com.stcloud.core.service.CloudStorageService cloudStorageService;

    @Override
    public IPage<UserManageVO> listUsers(int page, int size) {
        checkAdmin();
        Page<SysUser> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .orderByDesc(SysUser::getCreatedAt);
        IPage<SysUser> userPage = sysUserMapper.selectPage(pageParam, wrapper);
        return userPage.convert(this::toVO);
    }

    @Override
    public UserManageVO getUser(Long userId) {
        checkAdmin();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return toVO(user);
    }

    @Override
    @Transactional
    public void updateUser(Long userId, UpdateUserRequest request) {
        checkAdmin();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        String encodedPassword = request.getResetPassword() != null && !request.getResetPassword().isBlank()
                ? passwordEncoder.encode(request.getResetPassword()) : null;
        userSecurityService.lockTenant(user.getTenantId());
        // 加锁读返回最新已提交状态，避免事务开始时读到的实体覆盖并发改密或禁用。
        user = sysUserMapper.selectForSecurityUpdate(user.getTenantId(), userId);
        if (user == null) throw new BusinessException(ResultCode.USER_NOT_FOUND);

        boolean revoke = false;
        LambdaUpdateWrapper<SysUser> fields = new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId).eq(SysUser::getTenantId, user.getTenantId());
        boolean changed = false;
        if (request.getNickname() != null) {
            fields.set(SysUser::getNickname, request.getNickname());
            changed = true;
        }
        if (request.getStatus() != null) {
            if (!request.getStatus().equals(user.getStatus())) revoke = true;
            fields.set(SysUser::getStatus, request.getStatus());
            changed = true;
            // 禁用用户时吊销其 refresh token，禁止刷新会话
            if (request.getStatus() == UserStatus.DISABLED.getCode()) {
                revoke = true;
            }
        }
        if (request.getStorageQuota() != null) {
            cloudStorageService.validateQuotaAssignment(user.getStorageQuota(), request.getStorageQuota());
            fields.set(SysUser::getStorageQuota, request.getStorageQuota());
            changed = true;
        }
        if (encodedPassword != null) {
            fields.set(SysUser::getPassword, encodedPassword);
            changed = true;
            revoke = true;
        }
        // 只提交请求指定的字段，不能把旧实体中的密码、状态和安全版本整行写回。
        if (changed && sysUserMapper.update(null, fields) != 1) {
            throw new IllegalStateException("用户更新失败");
        }
        if (revoke) {
            userSecurityService.increment(user.getTenantId(), userId);
            Long revokedUserId = userId;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { authService.revokeRefreshToken(revokedUserId); }
            });
        }
        log.info("管理员{}更新用户: userId={}", UserContext.getUserId(), userId);
    }

    @Override
    @Transactional
    public void deleteUser(Long userId) {
        checkAdmin();
        if (userId.equals(UserContext.getUserId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不能删除自己");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) throw new BusinessException(ResultCode.USER_NOT_FOUND);
        userSecurityService.lockTenant(user.getTenantId());
        userSecurityService.increment(user.getTenantId(), userId);
        sysUserMapper.deleteById(userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { authService.revokeRefreshToken(userId); }
        });
        log.info("管理员{}删除用户: userId={}", UserContext.getUserId(), userId);
    }

    @Override
    @Transactional
    public UserManageVO createUser(CreateUserRequest request) {
        checkAdmin();
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "密码不能为空");
        }
        Long existing = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername()));
        if (existing > 0) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        String nickname = (request.getNickname() != null && !request.getNickname().isBlank())
                ? request.getNickname() : request.getUsername();
        user.setNickname(nickname);
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        // 新建用户默认正常
        user.setStatus(UserStatus.NORMAL.getCode());
        user.setSecurityVersion(0L);
        user.setStorageUsed(0L);
        user.setStorageQuota(DEFAULT_QUOTA);
        sysUserMapper.insert(user);

        // 分配角色：优先使用请求指定的角色，否则分配默认 user 角色
        // （tenant_id 由 MetaObjectHandler / assignRolesToUser 自动填充）
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            roleService.assignRolesToUser(user.getId(), request.getRoleIds());
        } else {
            SysRole defaultRole = sysRoleMapper.selectOne(
                    new LambdaQueryWrapper<SysRole>()
                            .eq(SysRole::getRoleCode, "user")
                            .eq(SysRole::getStatus, 1)
                            .last("LIMIT 1"));
            if (defaultRole != null) {
                roleService.assignRolesToUser(user.getId(), List.of(String.valueOf(defaultRole.getId())));
            }
        }

        log.info("管理员{}创建用户: username={}, userId={}", UserContext.getUserId(), user.getUsername(), user.getId());
        return toVO(user);
    }
    private void checkAdmin() {
        if (!UserContext.hasPermission("admin:user:manage")) {
            throw new BusinessException(ResultCode.FORBIDDEN, "需要管理员权限");
        }
    }

    private UserManageVO toVO(SysUser user) {
        UserManageVO vo = new UserManageVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setAvatar(user.getAvatar());
        vo.setStatus(user.getStatus());
        vo.setStorageUsed(user.getStorageUsed());
        vo.setStorageQuota(user.getStorageQuota());
        vo.setRoles(roleService.getUserRoles(user.getId()));
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
