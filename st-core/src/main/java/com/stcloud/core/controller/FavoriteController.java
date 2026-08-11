package com.stcloud.core.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.response.Result;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "文件收藏", description = "文件收藏管理")
@RestController
@RequestMapping("/api/favorite")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class FavoriteController {

    @Resource
    private FavoriteService favoriteService;

    @Operation(summary = "切换收藏状态")
    @PostMapping("/{nodeId}")
    public Result<Boolean> toggleFavorite(@PathVariable Long nodeId) {
        return Result.success(favoriteService.toggleFavorite(nodeId));
    }

    @Operation(summary = "收藏列表（含文件元数据）")
    @GetMapping("/list")
    public Result<List<FileNodeVO>> listFavorites() {
        return Result.success(favoriteService.listFavorites());
    }

    @Operation(summary = "收藏ID列表（轻量）")
    @GetMapping("/ids")
    public Result<List<Long>> listFavoriteIds() {
        return Result.success(favoriteService.listFavoriteIds());
    }
    @Operation(summary = "收藏列表（分页）")
    @GetMapping("/page")
    public Result<IPage<FileNodeVO>> pageFavorites(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return Result.success(favoriteService.pageFavorites(page, size));
    }
}