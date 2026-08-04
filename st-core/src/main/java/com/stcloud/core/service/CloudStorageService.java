package com.stcloud.core.service;

/**
 * 云盘总容量服务 - 校验个人与团队存储用量之和不超过云盘总容量上限
 */
public interface CloudStorageService {

    /**
     * 校验写入 delta 字节后是否超出云盘总容量，超出则抛出业务异常
     *
     * @param delta 即将增加的字节数（必须 >= 0）
     */
    void checkCapacity(long delta);

    /**
     * 校验配额分配：单个配额不超过云盘总容量，且调整后全部分配配额总和不超过总容量。
     * 在管理员设置用户配额或团队空间配额时调用。
     *
     * @param oldQuota 该对象当前的配额（用于计算增量，null/0 视为 0）
     * @param newQuota 即将设置的新配额（null/0 表示不限，跳过校验）
     */
    void validateQuotaAssignment(Long oldQuota, Long newQuota);
}
