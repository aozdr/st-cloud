package com.stcloud.team.controller;

import com.stcloud.common.response.Result;
import com.stcloud.core.dto.ChunkUrlResponse;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.dto.RelayChunkResponse;
import com.stcloud.core.dto.UploadCheckRequest;
import com.stcloud.core.dto.UploadCheckResponse;
import com.stcloud.core.dto.UploadInitRequest;
import com.stcloud.core.dto.UploadInitResponse;
import com.stcloud.core.dto.UploadMergeRequest;
import com.stcloud.core.dto.UploadStatusResponse;
import com.stcloud.core.service.UploadService;
import com.stcloud.team.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 团队上传专用入口：所有写入都携带明确 spaceId，并在服务调用前完成团队 ACL 校验。
 * generic /api/file/upload/* 不接受团队 scope，避免客户端伪造 spaceId 绕过团队权限。
 */
@Tag(name = "团队文件上传", description = "团队空间内的秒传、简单上传和分片上传")
@RestController
@RequestMapping("/api/team/{spaceId}/files/upload")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class TeamUploadController {

    private final TeamService teamService;
    private final UploadService uploadService;

    @Operation(summary = "团队空间秒传检查")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/check")
    public Result<UploadCheckResponse> check(@PathVariable Long spaceId,
                                             @Valid @RequestBody UploadCheckRequest request) {
        requireUpload(spaceId, request.getParentId());
        return Result.success(uploadService.checkTeamInstantUpload(spaceId, request));
    }

    @Operation(summary = "团队空间分片上传初始化")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/init")
    public Result<UploadInitResponse> init(@PathVariable Long spaceId,
                                           @Valid @RequestBody UploadInitRequest request) {
        requireUpload(spaceId, request.getParentId());
        if (request.getReplaceFileId() != null && request.getReplaceFileId() > 0) {
            teamService.requirePermissions(spaceId, request.getReplaceFileId(), "upload");
        }
        return Result.success(uploadService.initTeamChunkedUpload(spaceId, request));
    }

    @Operation(summary = "团队空间简单上传")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/simple")
    public Result<FileNodeVO> simple(@PathVariable Long spaceId,
                                     @RequestParam(required = false, defaultValue = "0") Long parentId,
                                     @RequestPart("file") MultipartFile file) {
        requireUpload(spaceId, parentId);
        return Result.success(uploadService.simpleTeamUpload(spaceId, parentId, file));
    }

    @Operation(summary = "查询团队上传状态")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @GetMapping("/status")
    public Result<UploadStatusResponse> status(@PathVariable Long spaceId,
                                               @RequestParam String uploadId,
                                               @RequestParam String s3UploadId) {
        requireSessionUpload(spaceId, uploadId);
        return Result.success(uploadService.getTeamUploadStatus(spaceId, uploadId, s3UploadId));
    }

    @Operation(summary = "获取团队上传分片 URL")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @GetMapping("/chunk-url")
    public Result<ChunkUrlResponse> chunkUrl(@PathVariable Long spaceId,
                                             @RequestParam String uploadId,
                                             @RequestParam String s3UploadId,
                                             @RequestParam int chunkIndex,
                                             @RequestParam(required = false) Integer clientLimit) {
        requireSessionUpload(spaceId, uploadId);
        return Result.success(uploadService.getTeamChunkUrl(spaceId, uploadId, s3UploadId,
                chunkIndex, clientLimit));
    }

    @Operation(summary = "确认团队上传分片")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/chunk-confirm")
    public Result<Void> confirm(@PathVariable Long spaceId,
                                @RequestParam String uploadId,
                                @RequestParam String s3UploadId,
                                @RequestParam int chunkIndex) {
        requireSessionUpload(spaceId, uploadId);
        uploadService.confirmTeamChunk(spaceId, uploadId, s3UploadId, chunkIndex);
        return Result.success();
    }

    @Operation(summary = "合并团队上传分片")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/merge")
    public Result<FileNodeVO> merge(@PathVariable Long spaceId,
                                    @Valid @RequestBody UploadMergeRequest request) {
        requireSessionUpload(spaceId, request.getUploadId());
        return Result.success(uploadService.mergeTeamChunks(spaceId, request));
    }

    @Operation(summary = "中止团队上传")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @DeleteMapping("/abort")
    public Result<Void> abort(@PathVariable Long spaceId,
                              @RequestParam String uploadId,
                              @RequestParam String s3UploadId,
                              @RequestParam(required = false) Long fileId) {
        requireSessionUpload(spaceId, uploadId);
        uploadService.abortTeamUpload(spaceId, uploadId, s3UploadId, fileId);
        return Result.success();
    }

    @Operation(summary = "中转上传团队小块")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/relay-chunk")
    public Result<RelayChunkResponse> relayChunk(@PathVariable Long spaceId,
                                                 @RequestParam String uploadId,
                                                 @RequestParam String s3UploadId,
                                                 @RequestParam int seq,
                                                 HttpServletRequest request) throws IOException {
        requireSessionUpload(spaceId, uploadId);
        return Result.success(uploadService.relayTeamChunk(spaceId, uploadId, s3UploadId, seq,
                request.getInputStream(), request.getContentLengthLong()));
    }

    @Operation(summary = "完成团队中转上传")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/relay-finalize")
    public Result<FileNodeVO> relayFinalize(@PathVariable Long spaceId,
                                            @RequestParam String uploadId,
                                            @RequestParam String s3UploadId) {
        requireSessionUpload(spaceId, uploadId);
        return Result.success(uploadService.relayTeamFinalize(spaceId, uploadId, s3UploadId));
    }

    private void requireUpload(Long spaceId, Long parentId) {
        teamService.requirePermissions(spaceId, parentId, "upload");
    }

    private void requireSessionUpload(Long spaceId, String uploadId) {
        Long nodeId = uploadService.getOwnedUploadSessionNodeId(spaceId, uploadId);
        teamService.requirePermissions(spaceId, nodeId, "upload");
    }
}
