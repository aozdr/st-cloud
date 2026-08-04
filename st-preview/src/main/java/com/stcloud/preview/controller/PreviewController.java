package com.stcloud.preview.controller;

import com.stcloud.common.response.Result;
import com.stcloud.preview.dto.PreviewResultVO;
import com.stcloud.preview.service.PreviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "文件预览", description = "图片缩略图、视频播放、PDF、文本预览")
@RestController
@RequestMapping("/api/preview")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PreviewController {

    private final PreviewService previewService;

    @Operation(summary = "获取文件预览")
    @GetMapping("/{nodeId}")
    public Result<PreviewResultVO> preview(@PathVariable Long nodeId) {
        return Result.success(previewService.preview(nodeId));
    }

    @Operation(summary = "获取图片缩略图URL")
    @GetMapping("/{nodeId}/thumbnail")
    public Result<String> getThumbnail(
            @PathVariable Long nodeId,
            @RequestParam(defaultValue = "md") String size) {
        return Result.success(previewService.getThumbnailUrl(nodeId, size));
    }

    @Operation(summary = "获取视频播放URL")
    @GetMapping("/{nodeId}/video")
    public Result<PreviewResultVO> getVideoPreview(@PathVariable Long nodeId) {
        return Result.success(previewService.getVideoPreview(nodeId));
    }
}
