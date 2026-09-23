package com.stcloud.team.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.response.Result;
import com.stcloud.team.dto.FileWatchStateVO;
import com.stcloud.team.dto.FileWatchVO;
import com.stcloud.team.service.FileWatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 文件关注 HTTP 接口；主体由 UserContext 注入，不接收 userId。 */
@Tag(name = "文件关注", description = "文件关注与变更提醒")
@RestController
@RequestMapping("/api/file-watches")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class FileWatchController {

    private final FileWatchService fileWatchService;

    @Operation(summary = "查询文件关注状态")
    @GetMapping("/state")
    public Result<FileWatchStateVO> state(@RequestParam Long nodeId) {
        return fileWatchService.state(nodeId);
    }

    @Operation(summary = "关注文件")
    @PutMapping("/{nodeId}")
    public Result<FileWatchStateVO> watch(@PathVariable Long nodeId) {
        return fileWatchService.watch(nodeId);
    }

    @Operation(summary = "取消关注文件")
    @DeleteMapping("/{nodeId}")
    public Result<Void> unwatch(@PathVariable Long nodeId) {
        return fileWatchService.unwatch(nodeId);
    }

    @Operation(summary = "我的关注列表")
    @GetMapping
    public Result<IPage<FileWatchVO>> list(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return fileWatchService.list(page, size);
    }
}
