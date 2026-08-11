package com.stcloud.sync.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 同步冲突记录实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sync_conflict")
public class SyncConflict extends BaseEntity {

    private Long syncRootId;
    private Long userId;
    private String relativePath;
    private String localMd5;
    private String cloudMd5;
    private String status;      // pending / resolved
    private String resolution;  // keep_both / server_wins / local_wins
}