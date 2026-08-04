package com.stcloud.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.admin.dto.AssignPermissionsRequest;
import com.stcloud.admin.dto.CreateRoleRequest;
import com.stcloud.admin.dto.PermissionVO;
import com.stcloud.admin.dto.RoleVO;
import com.stcloud.admin.service.RoleService;
import com.stcloud.auth.entity.SysPermission;
import com.stcloud.auth.entity.SysRole;
import com.stcloud.auth.entity.SysRolePermission;
import com.stcloud.auth.entity.SysUserRole;
import com.stcloud.auth.mapper.SysPermissionMapper;
import com.stcloud.auth.mapper.SysRoleMapper;
import com.stcloud.auth.mapper.SysRolePermissionMapper;
import com.stcloud.auth.mapper.SysUserRoleMapper;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RoleServiceImpl implements RoleService {

    @Resource
    private SysRoleMapper roleMapper;

    @Resource
    private SysPermissionMapper permissionMapper;

    @Resource
    private SysRolePermissionMapper rolePermissionMapper;

    @Resource
    private SysUserRoleMapper userRoleMapper;

    @Override
    public List<RoleVO> listRoles() {
        List<SysRole> roles = roleMapper.selectList(
                new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getCreatedAt));
        return roles.stream().map(role -> {
            RoleVO vo = toVO(role);
            vo.setPermissions(getRolePermissions(role.getId()));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public RoleVO getRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "角色不存在");
        }
        RoleVO vo = toVO(role);
        vo.setPermissions(getRolePermissions(roleId));
        return vo;
    }

    @Override
    @Transactional
    public RoleVO createRole(CreateRoleRequest request) {
        // 检查角色编码是否已存在
        Long count = roleMapper.selectCount(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, request.getRoleCode()));
        if (count > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "角色编码已存在");
        }

        SysRole role = new SysRole();
        role.setRoleCode(request.getRoleCode());
        role.setRoleName(request.getRoleName());
        role.setDescription(request.getDescription());
        role.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        role.setBuiltIn(0);
        roleMapper.insert(role);
        log.info("创建角色: code={}, name={}", role.getRoleCode(), role.getRoleName());
        return toVO(role);
    }

    @Override
    @Transactional
    public RoleVO updateRole(Long roleId, CreateRoleRequest request) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "角色不存在");
        }

        role.setRoleName(request.getRoleName());
        role.setDescription(request.getDescription());
        if (request.getStatus() != null) {
            role.setStatus(request.getStatus());
        }
        roleMapper.updateById(role);
        log.info("更新角色: roleId={}", roleId);
        return toVO(role);
    }

    @Override
    @Transactional
    public void deleteRole(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "角色不存在");
        }
        if (role.getBuiltIn() == 1) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "内置角色不可删除");
        }

        // 删除角色-权限关联
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
        // 删除用户-角色关联
        userRoleMapper.delete(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, roleId));
        // 删除角色
        roleMapper.deleteById(roleId);
        log.info("删除角色: roleId={}", roleId);
    }

    @Override
    @Transactional
    public void assignPermissions(Long roleId, AssignPermissionsRequest request) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "角色不存在");
        }

        // 先删除旧关联
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));

        // 再插入新关联
        if (request.getPermissionIds() != null) {
            for (Long permId : request.getPermissionIds()) {
                SysRolePermission rp = new SysRolePermission();
                rp.setRoleId(roleId);
                rp.setPermissionId(permId);
                rolePermissionMapper.insert(rp);
            }
        }
        log.info("分配角色权限: roleId={}, permissionCount={}", roleId,
                request.getPermissionIds() != null ? request.getPermissionIds().size() : 0);
    }

    @Override
    @Transactional
    public void assignRolesToUser(Long userId, List<Long> roleIds) {
        // 先删除旧关联
        userRoleMapper.delete(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));

        // 再插入新关联
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                SysUserRole userRole = new SysUserRole();
                userRole.setUserId(userId);
                userRole.setRoleId(roleId);
                userRoleMapper.insert(userRole);
            }
        }
        log.info("分配用户角色: userId={}, roleCount={}", userId,
                roleIds != null ? roleIds.size() : 0);
    }

    @Override
    public List<RoleVO> getUserRoles(Long userId) {
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> roleIds = userRoles.stream()
                .map(SysUserRole::getRoleId)
                .collect(Collectors.toList());
        List<SysRole> roles = roleMapper.selectBatchIds(roleIds);
        return roles.stream().map(this::toVO).collect(Collectors.toList());
    }

    private List<PermissionVO> getRolePermissions(Long roleId) {
        List<SysRolePermission> rolePerms = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
        if (rolePerms.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> permIds = rolePerms.stream()
                .map(SysRolePermission::getPermissionId)
                .collect(Collectors.toList());
        List<SysPermission> perms = permissionMapper.selectBatchIds(permIds);
        return perms.stream().map(this::toPermissionVO).collect(Collectors.toList());
    }

    private RoleVO toVO(SysRole role) {
        RoleVO vo = new RoleVO();
        vo.setId(role.getId());
        vo.setRoleCode(role.getRoleCode());
        vo.setRoleName(role.getRoleName());
        vo.setDescription(role.getDescription());
        vo.setStatus(role.getStatus());
        vo.setBuiltIn(role.getBuiltIn() == 1);
        vo.setCreatedAt(role.getCreatedAt());
        return vo;
    }

    private PermissionVO toPermissionVO(SysPermission perm) {
        PermissionVO vo = new PermissionVO();
        vo.setId(perm.getId());
        vo.setPermissionCode(perm.getPermissionCode());
        vo.setPermissionName(perm.getPermissionName());
        vo.setModule(perm.getModule());
        vo.setDescription(perm.getDescription());
        return vo;
    }
}
