package com.stcloud.sync.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 同步排除路径实体
 * <p>
 * 记录同步根下不需要同步的子路径（文件夹或文件）。
 * delta 查询时过滤掉排除路径下的变更。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sync_exclusion")
public class SyncExclusion extends BaseEntity {

    private Long syncRootId;
    private Long userId;
    private String relativePath;
}