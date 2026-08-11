package com.stcloud.team.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 团队空间活动日志实体
 * 不继承 BaseEntity（无逻辑删除，日志只增不删，定时清理）
 */
@Data
@TableName("team_activity")
public class TeamActivity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long spaceId;
    private Long userId;
    private String username; // 冗余，便于展示
    private String nickname; // 冗余，便于展示
    private String action; // 操作类型
    private String targetType; // FILE/FOLDER/MEMBER/SPACE/INVITE
    private Long targetId;
    private String targetName;
    private String detail; // 操作详情(JSON)

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}