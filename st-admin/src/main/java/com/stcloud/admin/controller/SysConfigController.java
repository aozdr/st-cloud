package com.stcloud.admin.controller;

import com.stcloud.common.entity.SysConfig;
import com.stcloud.common.response.Result;
import com.stcloud.common.sysconfig.SysConfigService;
import com.stcloud.common.sysconfig.SysConfigServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "配置管理", description = "全局系统配置读写（可后台修改）")
@RestController
@RequestMapping("/api/admin/config")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:share:security') or hasRole('ADMIN')")
public class SysConfigController {

    private final SysConfigService sysConfigService;

    @Operation(summary = "分享安全配置列表")
    @GetMapping("/share-security")
    public Result<List<SysConfig>> shareSecurityList() {
        return Result.success(sysConfigService.listByGroup(SysConfigServiceImpl.SHARE_SECURITY_PREFIX));
    }

    @Operation(summary = "更新分享安全配置")
    @PutMapping("/share-security")
    public Result<Void> update(@Valid @RequestBody UpdateConfigRequest request) {
        sysConfigService.update(request.getKey(), request.getValue());
        return Result.success();
    }

    @Data
    public static class UpdateConfigRequest {
        @NotBlank(message = "配置键不能为空")
        private String key;

        @NotBlank(message = "配置值不能为空")
        private String value;
    }
}
