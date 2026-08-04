package com.stcloud.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role_permission")
public class SysRolePermission extends BaseEntity {

    private Long roleId;
    private Long permissionId;
}
