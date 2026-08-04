package com.stcloud.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stcloud.admin.dto.UpdateUserRequest;
import com.stcloud.admin.dto.UserManageVO;
import com.stcloud.admin.service.RoleService;
import com.stcloud.admin.service.UserManageService;
import com.stcloud.auth.entity.SysUser;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class UserManageServiceImpl implements UserManageService {

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private RoleService roleService;
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

        if (request.getNickname() != null) {
            user.setNickname(request.getNickname());
        }
        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }
        if (request.getStorageQuota() != null) {
            cloudStorageService.validateQuotaAssignment(user.getStorageQuota(), request.getStorageQuota());
            user.setStorageQuota(request.getStorageQuota());
        }
        if (request.getIsAdmin() != null) {
            user.setIsAdmin(request.getIsAdmin());
        }
        if (request.getResetPassword() != null && !request.getResetPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.getResetPassword()));
        }
        sysUserMapper.updateById(user);
        log.info("管理员{}更新用户: userId={}", UserContext.getUserId(), userId);
    }

    @Override
    @Transactional
    public void deleteUser(Long userId) {
        checkAdmin();
        if (userId.equals(UserContext.getUserId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不能删除自己");
        }
        sysUserMapper.deleteById(userId);
        log.info("管理员{}删除用户: userId={}", UserContext.getUserId(), userId);
    }

    private void checkAdmin() {
        if (!UserContext.isAdmin()) {
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
        vo.setIsAdmin(user.getIsAdmin());
        vo.setStorageUsed(user.getStorageUsed());
        vo.setStorageQuota(user.getStorageQuota());
        vo.setRoles(roleService.getUserRoles(user.getId()));
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
