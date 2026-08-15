package com.stcloud.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件对象（去重引用）。
 * 同租户内按 md5 唯一，物理对象只存一份；file_node 通过 objectId 引用本对象。
 * 引用计数 refCount = 同租户同 md5 的已完成 file_node 数，归零时才删除 S3 物理对象。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("file_object")
public class FileObject extends BaseEntity {

    /** 文件MD5 */
    private String md5;

    /** 文件大小(字节) */
    private Long size;

    /** 对象存储路径 */
    private String storagePath;

    /** 引用计数（同租户同 md5 的 file_node 数） */
    private Integer refCount;

    /** 状态：0-正常 1-已删除 */
    private Integer status;
}