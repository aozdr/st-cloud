package com.stcloud.core.controller;

import com.stcloud.common.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 存活探针：供 Nginx / 容器编排探活使用，不查询数据库与 Redis，
 * 避免依赖抖动把节点整体摘除。
 */
@Tag(name = "健康检查")
@RestController
public class HealthController {

    @Operation(summary = "服务存活状态")
    @GetMapping("/api/health")
    public Result<Map<String, Object>> health() {
        return Result.success(Map.of("status", "UP", "timestamp", System.currentTimeMillis()));
    }
}
