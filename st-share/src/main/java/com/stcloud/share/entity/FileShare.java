package com.stcloud.share.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("file_share")
public class FileShare extends BaseEntity {

    private String shareCode;       // 分享码(短链)
    private Long fileNodeId;        // 分享的文件节点ID
    private Long creatorId;         // 创建者ID
    private Integer shareType;      // 0-公开 1-私密(提取码)
    private String password;        // 提取码明文(旧数据可能为BCrypt)
    private LocalDateTime expireAt; // 过期时间,NULL=永久
    private Integer permission;     // 0-查看 1-下载 2-上传 3-编辑
    private Integer downloadLimit;  // 下载次数限制,NULL=不限
    private Integer downloadCount;  // 已下载次数
    private Integer viewCount;      // 访问次数
    private Integer status;         // 0-已取消 1-有效
}
