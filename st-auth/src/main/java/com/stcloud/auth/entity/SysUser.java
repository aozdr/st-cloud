package com.stcloud.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private String username;
    private String password;
    private String nickname;
    private String email;
    private String phone;
    private String avatar;
    private Integer status;
    private Integer isAdmin;
    private Long storageUsed;
    private Long storageQuota;
    private LocalDateTime lastLoginAt;
    private String lastLoginIp;
}
