package com.stcloud.core.service.impl;

import com.stcloud.core.entity.FileObject;
import com.stcloud.core.enums.FileObjectStatus;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.StorageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * 文件对象服务实现（去重/引用计数，TASK-001）。
 */
@Slf4j
@Service
public class FileObjectServiceImpl implements FileObjectService {

    @Resource
    private FileObjectMapper fileObjectMapper;

    @Resource
    private StorageService storageService;

    @Override
    public FileObject acquire(Long tenantId, String md5, long size, Supplier<String> storagePathSupplier) {
        if (md5 == null || md5.isEmpty()) {
            return null;
        }
        // 去重命中：直接复用已有对象并 +1 引用，不重复上传
        FileObject existing = fileObjectMapper.selectByTenantAndMd5(tenantId, md5);
        if (existing != null) {
            fileObjectMapper.incrementRefCount(existing.getId());
            return existing;
        }
        // 未命中：由调用方（事务内/事务外均可）先上传新物理对象，再按路径归属/创建对象记录
        String storagePath = storagePathSupplier.get();
        return acquireByPath(tenantId, md5, size, storagePath);
    }

    @Override
    public FileObject acquireByPath(Long tenantId, String md5, long size, String storagePath) {
        if (md5 == null || md5.isEmpty()) {
            return null;
        }
        // 仅 DB 操作（事务边界治理）：物理对象已上传，此处不再触发任何 S3 调用。
        // 先复用命中，再尝试创建，最后处理并发竞争，与 acquire 的"上传后归属"语义保持一致。
        FileObject existing = fileObjectMapper.selectByTenantAndMd5(tenantId, md5);
        if (existing != null) {
            fileObjectMapper.incrementRefCount(existing.getId());
            return existing;
        }
        FileObject created = new FileObject();
        created.setTenantId(tenantId);
        created.setMd5(md5);
        created.setSize(size);
        created.setStoragePath(storagePath);
        created.setRefCount(1);
        // 新建文件对象状态正常（status=0 正常，deleted=0 未删除）
        created.setStatus(FileObjectStatus.NORMAL.getCode());
        int inserted = fileObjectMapper.insertIgnore(created);
        if (inserted == 0) {
            // 并发首个上传竞争：另一事务已插入同 md5 对象，复用之（可能产生一次冗余上传，属可接受竞态）
            FileObject winner = fileObjectMapper.selectByTenantAndMd5(tenantId, md5);
            if (winner != null) {
                fileObjectMapper.incrementRefCount(winner.getId());
                return winner;
            }
            // 去重墓碑：同 md5 存在已软删除记录（uk_tenant_md5 唯一键保留但查询不可见）。
            // 物理对象已由 supplier 重新上传，恢复该记录并原子 +1 引用（ref_count 先置 0 再由 +1 保证并发正确）
            fileObjectMapper.revive(tenantId, md5, storagePath, size);
            winner = fileObjectMapper.selectByTenantAndMd5(tenantId, md5);
            if (winner != null) {
                fileObjectMapper.incrementRefCount(winner.getId());
                return fileObjectMapper.selectByTenantAndMd5(tenantId, md5);
            }
        }
        // 返回权威行（含数据库生成 id）
        return fileObjectMapper.selectByTenantAndMd5(tenantId, md5);
    }

    @Override
    public FileObject findByTenantAndMd5(Long tenantId, String md5) {
        if (md5 == null || md5.isEmpty()) {
            return null;
        }
        return fileObjectMapper.selectByTenantAndMd5(tenantId, md5);
    }

    @Override
    public int release(Long objectId) {
        if (objectId == null) {
            return 0;
        }
        fileObjectMapper.decrementRefCount(objectId);
        Integer remaining = fileObjectMapper.getRefCount(objectId);
        return remaining == null ? 0 : remaining;
    }

    @Override
    public void deletePhysical(Long objectId) {
        if (objectId == null) {
            return;
        }
        FileObject object = fileObjectMapper.selectById(objectId);
        if (object == null || object.getStoragePath() == null) {
            return;
        }
        try {
            storageService.deleteObject(object.getStoragePath());
        } finally {
            fileObjectMapper.markDeleted(objectId);
        }
    }
}
