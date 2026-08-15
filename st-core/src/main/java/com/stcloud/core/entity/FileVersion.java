package com.stcloud.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_version")
public class FileVersion {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long tenantId;
    private Long fileNodeId;
    private Integer versionNum;
    private Long fileSize;
    private String fileMd5;
    private String storagePath;
    private Long modifierId;
    private String modifierName;
    /** 版本来源：0-上传覆盖 / 1-在线编辑器保存（36 号脚本新增，D1 版本裁剪按此区分） */
    private Integer source;
    private LocalDateTime createdAt;
}
