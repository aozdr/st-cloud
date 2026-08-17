package com.stcloud.core.service;

import com.stcloud.core.entity.FileObject;

import java.util.function.Supplier;

/**
 * 文件对象服务（去重/引用计数，TASK-001）。
 * <p>
 * 职责：同租户内按 md5 去重；管理 file_object 引用计数；
 * 引用归零时由调用方按场景决定是否删除 S3 物理对象。
 */
public interface FileObjectService {

    /**
     * 获取或创建对象并 +1 引用。
     * <p>
     * 若同租户同 md5 对象已存在：直接复用（不调用 storagePathSupplier，不重复上传）。
     * 若不存在：调用 storagePathSupplier 上传新物理对象后创建对象（并发首传竞争安全）。
     *
     * @param tenantId           租户ID
     * @param md5                文件MD5
     * @param size               文件大小
     * @param storagePathSupplier 仅新建时需要上传物理对象的回调（返回对象key）
     * @return 权威对象（含 id/storagePath/refCount）
     */
    FileObject acquire(Long tenantId, String md5, long size, Supplier<String> storagePathSupplier);

    /**
     * 按路径获取或创建对象并 +1 引用（事务边界治理 F1-3/F2-1/F2-2）。
     * <p>
     * 与 {@link #acquire} 的唯一区别：物理对象已由调用方在事务外上传完成，
     * 本方法<b>只做 DB 操作</b>（select → 命中 incrementRefCount / 未命中 insertIgnore + 竞争复用），
     * 不触发任何 S3/上传调用。必须由事务内方法调用，保证对象记录与节点/配额同一事务。
     *
     * @param tenantId    租户ID
     * @param md5         文件MD5
     * @param size        文件大小
     * @param storagePath 物理对象存储路径（已上传）
     * @return 权威对象（含 id/storagePath/refCount）
     */
    FileObject acquireByPath(Long tenantId, String md5, long size, String storagePath);

    /**
     * 只读查询：按租户+MD5 查找正常对象（不改变引用计数）。用于秒传命中判定。
     */
    FileObject findByTenantAndMd5(Long tenantId, String md5);

    /**
     * 引用计数 -1（原子），返回剩余引用数。
     * <p>
     * 调用方根据场景决定是否物理删除：仅永久删除且剩余为 0 时调用 {@link #deletePhysical(Long)}；
     * 版本恢复/替换上传等场景只减引用、保留物理对象（可能仍被版本历史引用）。
     */
    int release(Long objectId);

    /**
     * 物理删除并标记对象失效（仅引用归零且需真正删除存储时调用）。
     * 幂等：对象已失效或不存在时直接返回。
     */
    void deletePhysical(Long objectId);
}
