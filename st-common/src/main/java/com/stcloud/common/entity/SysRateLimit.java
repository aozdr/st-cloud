package com.stcloud.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 传输限速规则实体 - 按用户或角色配置上传/下载速度上限(KB/s)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_rate_limit")
public class SysRateLimit extends BaseEntity {

    /** 规则名称 */
    private String ruleName;

    /** 限制范围:0-按用户 1-按角色 */
    private Integer scope;

    /** 用户ID 或 角色ID */
    private Long targetId;

    /** 匹配标识:角色编码(role) / 用户名(user) */
    private String targetCode;

    /** 展示名:昵称 / 角色名 */
    private String targetName;

    /** 上传限速 KB/s,0=不限速 */
    private Integer uploadSpeedLimit;

    /** 下载限速 KB/s,0=不限速 */
    private Integer downloadSpeedLimit;

    /** 0-禁用 1-启用 */
    private Integer enabled;

    /** 描述 */
    private String description;
}