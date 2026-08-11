package com.stcloud.sync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步变更日志实体
 * <p>
 * 自增 id 即同步游标，客户端通过 since=id 拉取增量变更。
 * 由 st-core 发布的 SyncChangeEvent 触发写入。
 */
@Data
@TableName("sync_change_log")
public class SyncChangeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long userId;
    private Long fileNodeId;

    /** CREATE / UPDATE / MOVE / RENAME / DELETE */
    private String changeType;

    /** 变更后完整路径 */
    private String path;

    /** 变更前完整路径（MOVE / RENAME） */
    private String oldPath;

    private String name;
    private Integer nodeType;
    private String fileMd5;
    private Long fileSize;
    private LocalDateTime createdAt;
}
