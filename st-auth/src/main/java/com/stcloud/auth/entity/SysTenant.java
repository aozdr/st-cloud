package com.stcloud.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_tenant")
public class SysTenant extends BaseEntity {

    private String tenantName;
    private String tenantCode;
    private String domain;
    private Integer status;
    private Long defaultQuota;
    private LocalDateTime expireAt;
}
