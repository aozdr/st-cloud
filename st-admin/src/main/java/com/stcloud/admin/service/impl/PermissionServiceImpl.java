package com.stcloud.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.admin.dto.PermissionVO;
import com.stcloud.admin.service.PermissionService;
import com.stcloud.auth.entity.SysPermission;
import com.stcloud.auth.mapper.SysPermissionMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PermissionServiceImpl implements PermissionService {

    @Resource
    private SysPermissionMapper permissionMapper;

    @Override
    public List<PermissionVO> listAllPermissions() {
        List<SysPermission> perms = permissionMapper.selectList(
                new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getModule));
        return perms.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public Map<String, List<PermissionVO>> listPermissionsByModule() {
        List<PermissionVO> all = listAllPermissions();
        return all.stream().collect(Collectors.groupingBy(PermissionVO::getModule));
    }

    private PermissionVO toVO(SysPermission perm) {
        PermissionVO vo = new PermissionVO();
        vo.setId(perm.getId());
        vo.setPermissionCode(perm.getPermissionCode());
        vo.setPermissionName(perm.getPermissionName());
        vo.setModule(perm.getModule());
        vo.setDescription(perm.getDescription());
        return vo;
    }
}
