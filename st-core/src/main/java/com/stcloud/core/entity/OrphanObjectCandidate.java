package com.stcloud.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 规范对象的孤儿回收候选。候选表是跨实例清理的协调边界，不能用 JVM 锁替代。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("file_orphan_candidate")
public class OrphanObjectCandidate extends BaseEntity {

    private String md5;
    private String storagePath;
    /** 尚未完成对象写入/提交的请求数，>0 时禁止回收。 */
    private Integer activeUploads;
    /** 0-active、1-pending、2-deleting。 */
    private Integer status;
    private LocalDateTime candidateAt;
    private LocalDateTime lastActiveAt;
}
