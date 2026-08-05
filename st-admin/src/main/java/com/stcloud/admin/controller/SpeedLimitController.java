package com.stcloud.admin.controller;

import com.stcloud.admin.dto.CreateSpeedLimitRequest;
import com.stcloud.admin.dto.SpeedLimitVO;
import com.stcloud.admin.service.SpeedLimitManageService;
import com.stcloud.common.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "限速管理", description = "用户/角色上传下载速度限速规则配置接口")
@RestController
@RequestMapping("/api/admin/speed-limit")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('transfer:speed:limit') or hasRole('ADMIN')")
public class SpeedLimitController {

    private final SpeedLimitManageService speedLimitManageService;

    @Operation(summary = "限速规则列表")
    @GetMapping("/list")
    public Result<List<SpeedLimitVO>> list() {
        return Result.success(speedLimitManageService.listRules());
    }

    @Operation(summary = "规则详情")
    @GetMapping("/{id}")
    public Result<SpeedLimitVO> get(@PathVariable Long id) {
        return Result.success(speedLimitManageService.getRule(id));
    }

    @Operation(summary = "创建限速规则")
    @PostMapping
    public Result<SpeedLimitVO> create(@RequestBody CreateSpeedLimitRequest request) {
        return Result.success(speedLimitManageService.createRule(request));
    }

    @Operation(summary = "编辑限速规则")
    @PutMapping("/{id}")
    public Result<SpeedLimitVO> update(@PathVariable Long id, @RequestBody CreateSpeedLimitRequest request) {
        return Result.success(speedLimitManageService.updateRule(id, request));
    }

    @Operation(summary = "删除限速规则")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        speedLimitManageService.deleteRule(id);
        return Result.success();
    }

    @Operation(summary = "启用/禁用限速规则")
    @PutMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id) {
        speedLimitManageService.toggleRule(id);
        return Result.success();
    }
}