package com.stcloud.team.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 文件关注事件的持久投递队列；状态更新与通知插入由投递事务统一提交。 */
@Data
@TableName("file_watch_delivery")
public class FileWatchDelivery implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long eventId;
    private Long userId;
    private Long nodeId;
    private Long spaceId;
    private Long actorId;
    private String changeType;
    private String watchIds;
    private String payload;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
