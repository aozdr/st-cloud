package com.stcloud.sync.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sync_root")
public class SyncRoot extends BaseEntity {

    private Long userId;
    private Long cloudFolderNodeId;
    private String localPathHint;
    private Integer status;   // 0-启用 1-暂停
    private String conflictStrategy;  // 冲突策略：keep_both / latest_wins / server_wins / local_wins
    private java.time.LocalDateTime lastSyncAt;  // 最后同步时间

    @TableField("sync_cursor")
    private Long syncCursor;  // 上次同步游标（sync_change_log.id）
}