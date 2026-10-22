package com.stcloud.core.controller;

import com.stcloud.common.ratelimit.RateLimitProperties;
import com.stcloud.common.ratelimit.RateLimitStats;
import com.stcloud.common.ratelimit.SpeedLimitResult;
import com.stcloud.common.ratelimit.SpeedLimitService;
import com.stcloud.common.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "传输管理", description = "传输限速查询")
@RestController
@RequestMapping("/api/transfer")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class TransferController {

    private final SpeedLimitService speedLimitService;
    private final RateLimitStats rateLimitStats;
    private final RateLimitProperties rateLimitProperties;

    @Operation(summary = "获取当前用户生效的上传/下载限速(KB/s,0=不限)")
    @GetMapping("/speed-limit")
    public Result<SpeedLimitResult> getSpeedLimit() {
        return Result.success(speedLimitService.resolve());
    }

    /** 多节点限速排查：当前模式与信用桶计数（仅管理员）。 */
    @Operation(summary = "限速运行指标")
    @GetMapping("/rate-limit/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> rateLimitStats() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mode", rateLimitProperties.getMode());
        snapshot.putAll(rateLimitStats.snapshot());
        return Result.success(snapshot);
    }
}