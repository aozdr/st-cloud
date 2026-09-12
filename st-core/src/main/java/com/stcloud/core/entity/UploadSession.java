package com.stcloud.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分片上传会话：服务端保存用户、租户、空间、节点及对象存储上下文，后续请求不得信任客户端重复提交的标识。
 */
@Data
@TableName("upload_session")
public class UploadSession {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long tenantId;
    private String uploadId;
    private Long userId;
    private Long fileNodeId;
    private Long spaceId;
    private String storagePath;
    @TableField("s3_upload_id")
    private String s3UploadId;
    private Long fileSize;
    private String fileMd5;
    private Integer totalChunks;
    private Long chunkSize;
    private Integer clientLimit;
    /** 0-active、1-merging、2-completed、3-aborted、4-failed、5-expired。 */
    private Integer status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
