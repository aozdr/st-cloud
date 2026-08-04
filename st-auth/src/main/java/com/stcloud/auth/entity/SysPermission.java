package com.stcloud.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 权限定义表 -- 全局系统数据，不按租户隔离，不继承 BaseEntity
 */
@Data
@TableName("sys_permission")
public class SysPermission implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String permissionCode;
    private String permissionName;
    private String module;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
