package com.stcloud.admin.service;

import com.stcloud.admin.dto.CreateRoleRequest;
import com.stcloud.admin.dto.RoleVO;
import com.stcloud.admin.dto.AssignPermissionsRequest;

import java.util.List;

public interface RoleService {

    List<RoleVO> listRoles();

    RoleVO getRole(Long roleId);

    RoleVO createRole(CreateRoleRequest request);

    RoleVO updateRole(Long roleId, CreateRoleRequest request);

    void deleteRole(Long roleId);

    void assignPermissions(Long roleId, AssignPermissionsRequest request);

    void assignRolesToUser(Long userId, List<String> roleIds);

    List<RoleVO> getUserRoles(Long userId);
}
