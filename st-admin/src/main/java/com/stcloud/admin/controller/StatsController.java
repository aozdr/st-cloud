package com.stcloud.admin.controller;

import com.stcloud.admin.dto.StatsVO;
import com.stcloud.admin.service.StatsService;
import com.stcloud.common.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "系统统计", description = "系统监控统计数据")
@RestController
@RequestMapping("/api/admin/stats")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:stats:view') or hasRole('ADMIN')")
public class StatsController {

    private final StatsService statsService;

    @Operation(summary = "获取系统统计数据")
    @GetMapping
    public Result<StatsVO> getStats() {
        return Result.success(statsService.getStats());
    }
}
