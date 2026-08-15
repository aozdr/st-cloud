package com.stcloud.core.service.impl;

import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.common.utils.FileSizeUtil;
import com.stcloud.core.mapper.CloudCapacityMapper;
import com.stcloud.core.service.CloudStorageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CloudStorageServiceImpl implements CloudStorageService {

    @Resource
    private CloudCapacityMapper cloudCapacityMapper;

    @Override
    public void checkCapacity(long delta) {
        if (delta <= 0) {
            return;
        }
        Long tenantId = UserContext.getTenantId();
        if (tenantId == null) {
            return;
        }
        // 行锁读取：云盘总容量已配置时，并发上传的总容量校验串行化（TASK-003）
        Long total = cloudCapacityMapper.getCloudTotalCapacityForUpdate(tenantId);
        // 总容量为空表示不限
        if (total == null || total <= 0) {
            return;
        }
        Long used = cloudCapacityMapper.sumCloudStorageUsed(tenantId);
        long currentUsed = used == null ? 0 : used;
        if (currentUsed + delta > total) {
            log.warn("云盘总容量不足: used={}, delta={}, total={}", currentUsed, delta, total);
            throw new BusinessException(ResultCode.CLOUD_CAPACITY_EXCEEDED);
        }
    }

    @Override
    public void validateQuotaAssignment(Long oldQuota, Long newQuota) {
        if (newQuota == null || newQuota <= 0) {
            // 新配额为不限，无需校验
            return;
        }
        Long tenantId = UserContext.getTenantId();
        if (tenantId == null) {
            return;
        }
        // 行锁读取：云盘总容量已配置时，并发上传的总容量校验串行化（TASK-003）
        Long total = cloudCapacityMapper.getCloudTotalCapacityForUpdate(tenantId);
        if (total == null || total <= 0) {
            // 云盘总容量不限
            return;
        }
        long old = oldQuota == null ? 0 : oldQuota;
        // 已分配给其他对象的配额总和（不含当前被调整对象）
        long sumUserQuota = orZero(cloudCapacityMapper.sumUserQuota(tenantId));
        long sumTeamQuota = orZero(cloudCapacityMapper.sumTeamQuota(tenantId));
        long othersAllocated = sumUserQuota + sumTeamQuota - old;
        long remaining = total - othersAllocated; // 当前可分配给本对象的剩余容量
        // 单个配额不得超过云盘总容量
        if (newQuota > total) {
            String msg = "配额不能超过云盘总容量 " + FileSizeUtil.format(total)
                    + "，当前剩余可分配 " + FileSizeUtil.format(remaining);
            throw new BusinessException(ResultCode.CLOUD_CAPACITY_EXCEEDED.getCode(), msg);
        }
        // 调整后全部分配配额总和不得超过云盘总容量
        long newSum = othersAllocated + newQuota;
        if (newSum > total) {
            String msg = "配额超出，当前剩余可分配 " + FileSizeUtil.format(remaining)
                    + "（云盘总容量 " + FileSizeUtil.format(total) + "），请减少配额或扩大云盘总容量";
            throw new BusinessException(ResultCode.CLOUD_CAPACITY_EXCEEDED.getCode(), msg);
        }
    }

    private long orZero(Long v) {
        return v == null ? 0 : v;
    }
}
