package com.stcloud.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 全局配置实体（key-value）。可后台修改，运行时读取并缓存。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_config")
public class SysConfig extends BaseEntity {

    /** 配置键，如 share.brute_force.maxFailPerCode */
    private String configKey;

    /** 配置值（字符串存储，按需解析为 int/bool） */
    private String configValue;

    /** 分组前缀，便于后台按组展示，如 share.brute_force. */
    private String configGroup;

    /** 备注 */
    private String remark;

    /** 0-禁用 1-启用 */
    private Integer enabled;
}
