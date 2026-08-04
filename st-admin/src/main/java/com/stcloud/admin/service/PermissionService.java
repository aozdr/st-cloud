package com.stcloud.admin.service;

import com.stcloud.admin.dto.PermissionVO;

import java.util.List;
import java.util.Map;

public interface PermissionService {

    List<PermissionVO> listAllPermissions();

    Map<String, List<PermissionVO>> listPermissionsByModule();
}
