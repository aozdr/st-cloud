package com.stcloud.team.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内通知实体
 * 不继承 BaseEntity（无逻辑删除，通知只增不删）
 */
@Data
@TableName("notification")
public class Notification implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long userId;
    private String type;       // MENTION/TEAM_INVITE/FILE_CHANGE/MEMBER_CHANGE
    private String title;
    private String content;
    private String refType;    // team/comment/file
    private Long refId;
    /** 新版 FILE_CHANGE 关联的 Outbox 事件；旧通知保持 NULL。 */
    private Long eventId;
    private Long nodeId;
    private Long spaceId;
    private String changeType;
    @TableField("`read`")
    private Integer read;      // 0-未读 1-已读；MySQL 保留字，生成 SQL 时必须转义

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
