package com.stcloud.admin.controller;

import com.stcloud.admin.dto.CloudCapacityRequest;
import com.stcloud.admin.mapper.StatsMapper;
import com.stcloud.common.response.Result;
import com.stcloud.common.utils.FileSizeUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "云盘容量", description = "云盘总容量查看与设置")
@RestController
@RequestMapping("/api/admin/cloud-capacity")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:storage:manage') or hasRole('ADMIN')")
public class CloudCapacityController {

    private final StatsMapper statsMapper;

    @Operation(summary = "查询云盘总容量")
    @GetMapping
    public Result<Map<String, Object>> get() {
        Map<String, Object> data = new HashMap<>();
        data.put("cloudTotalCapacity", statsMapper.getCloudTotalCapacity());
        data.put("cloudStorageUsed", statsMapper.sumCloudStorageUsed());
        return Result.success(data);
    }

    @Operation(summary = "设置云盘总容量")
    @PutMapping
    public Result<Void> set(@RequestBody CloudCapacityRequest request) {
        Long capacity = request.getCapacity();
        // null 或 0 表示不限，直接放行
        if (capacity != null && capacity > 0) {
            Long used = statsMapper.sumCloudStorageUsed();
            long currentUsed = used == null ? 0 : used;
            if (capacity < currentUsed) {
                throw new com.stcloud.common.exception.BusinessException(
                        com.stcloud.common.response.ResultCode.CLOUD_CAPACITY_EXCEEDED.getCode(),
                        "云盘总容量不能小于当前已用容量 " + FileSizeUtil.format(currentUsed));
            }
        }
        statsMapper.setCloudTotalCapacity(capacity);
        return Result.success();
    }
}
