package com.stcloud.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity {

    private String roleCode;
    private String roleName;
    private String description;
    private Integer status;
    private Integer builtIn;
    /** 数据范围：1-本人 2-租户 3-全部 */
    private Integer dataScope;
    /** 扩展数据 JSON（如限速配置） */
    private String data;
}
