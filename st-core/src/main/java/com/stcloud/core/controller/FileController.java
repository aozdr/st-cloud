package com.stcloud.core.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.annotation.Auditable;
import com.stcloud.common.response.Result;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.utils.JwtUtils;
import com.stcloud.core.dto.*;
import com.stcloud.core.service.DownloadService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@Tag(name = "文件管理", description = "文件上传、下载、目录管理")
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class FileController {

    private final FileService fileService;
    private final UploadService uploadService;
    private final DownloadService downloadService;
    private final JwtUtils jwtUtils;

    // ==================== 目录管理 ====================

    @Operation(summary = "创建文件夹")
    @Auditable(action = "CREATE_FOLDER", targetType = "FOLDER")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/folder")
    public Result<FileNodeVO> createFolder(@Valid @RequestBody CreateFolderRequest request) {
        return Result.success(fileService.createFolder(request.getParentId(), request.getFolderName()));
    }


    @Operation(summary = "根据路径解析文件夹")
    @GetMapping("/by-path")
    public Result<FileNodeVO> resolveByPath(@RequestParam(required = false) String path) {
        return Result.success(fileService.resolveByPath(path));
    }

    @Operation(summary = "列出目录内容")
    @GetMapping("/list")
    public Result<IPage<FileNodeVO>> listDirectory(
            @RequestParam(defaultValue = "0") Long parentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return Result.success(fileService.listDirectory(parentId, page, size));
    }

    @Operation(summary = "获取文件/文件夹详情")
    @GetMapping("/{nodeId}")
    public Result<FileNodeVO> getNodeDetail(@PathVariable Long nodeId) {
        return Result.success(fileService.getNodeDetail(nodeId));
    }

    @Operation(summary = "重命名")
    @Auditable(action = "RENAME", targetType = "FILE", targetIdParam = "nodeId")
    @PreAuthorize("hasAuthority('file:rename') or hasRole('ADMIN')")
    @PutMapping("/{nodeId}/rename")
    public Result<FileNodeVO> rename(@PathVariable Long nodeId,
                                     @Valid @RequestBody RenameRequest request) {
        return Result.success(fileService.rename(nodeId, request.getNewName()));
    }

    @Operation(summary = "移动")
    @Auditable(action = "MOVE", targetType = "FILE")
    @PreAuthorize("hasAuthority('file:move') or hasRole('ADMIN')")
    @PostMapping("/move")
    public Result<Void> move(@Valid @RequestBody MoveRequest request) {
        fileService.move(request.getNodeIds(), request.getTargetParentId());
        return Result.success();
    }

    @Operation(summary = "复制")
    @Auditable(action = "COPY", targetType = "FILE")
    @PreAuthorize("hasAuthority('file:copy') or hasRole('ADMIN')")
    @PostMapping("/copy")
    public Result<Void> copy(@Valid @RequestBody MoveRequest request) {
        fileService.copy(request.getNodeIds(), request.getTargetParentId());
        return Result.success();
    }

    @Operation(summary = "删除至回收站")
    @Auditable(action = "DELETE", targetType = "FILE")
    @PreAuthorize("hasAuthority('file:delete') or hasRole('ADMIN')")
    @PostMapping("/delete")
    public Result<Void> deleteToRecycleBin(@Valid @RequestBody BatchIdsRequest request) {
        fileService.deleteToRecycleBin(request.getNodeIds());
        return Result.success();
    }

    @Operation(summary = "获取文件夹树")
    @GetMapping("/tree")
    public Result<List<FileTreeNodeVO>> getFolderTree() {
        return Result.success(fileService.getFolderTree());
    }

    @Operation(summary = "获取存储使用信息")
    @GetMapping("/storage")
    public Result<StorageInfoVO> getStorageInfo() {
        return Result.success(fileService.getStorageInfo());
    }

    // ==================== 文件上传 ====================

    @Operation(summary = "秒传检查")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/upload/check")
    public Result<UploadCheckResponse> checkInstantUpload(@Valid @RequestBody UploadCheckRequest request) {
        return Result.success(uploadService.checkInstantUpload(request));
    }

    @Operation(summary = "分片上传初始化")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/upload/init")
    public Result<UploadInitResponse> initChunkedUpload(@Valid @RequestBody UploadInitRequest request) {
        return Result.success(uploadService.initChunkedUpload(request));
    }

    @Operation(summary = "查询上传状态（断点续传）")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @GetMapping("/upload/status")
    public Result<UploadStatusResponse> getUploadStatus(
            @RequestParam String uploadId,
            @RequestParam String s3UploadId) {
        return Result.success(uploadService.getUploadStatus(uploadId, s3UploadId));
    }

    @Operation(summary = "合并分片")
    @Auditable(action = "UPLOAD", targetType = "FILE", targetIdParam = "fileId", detail = "分片上传合并完成")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/upload/merge")
    public Result<FileNodeVO> mergeChunks(@Valid @RequestBody UploadMergeRequest request) {
        return Result.success(uploadService.mergeChunks(request));
    }

    @Operation(summary = "中止分片上传")
    @Auditable(action = "ABORT_UPLOAD", targetType = "FILE")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @DeleteMapping("/upload/abort")
    public Result<Void> abortUpload(
            @RequestParam String uploadId,
            @RequestParam String s3UploadId,
            @RequestParam Long fileId) {
        uploadService.abortUpload(uploadId, s3UploadId, fileId);
        return Result.success();
    }

    @Operation(summary = "获取分片上传URL（限速门控）")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @GetMapping("/upload/chunk-url")
    public Result<ChunkUrlResponse> getChunkUrl(
            @RequestParam String uploadId,
            @RequestParam String s3UploadId,
            @RequestParam int chunkIndex,
            @RequestParam(required = false) Integer clientLimit) {
        return Result.success(uploadService.getChunkUrl(uploadId, s3UploadId, chunkIndex, clientLimit));
    }

    @Operation(summary = "确认分片上传完成（释放限速配额）")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/upload/chunk-confirm")
    public Result<Void> confirmChunk(
            @RequestParam String uploadId,
            @RequestParam String s3UploadId,
            @RequestParam int chunkIndex) {
        uploadService.confirmChunk(uploadId, s3UploadId, chunkIndex);
        return Result.success();
    }

    // ==================== 文件下载 ====================

    @Operation(summary = "流式下载单文件（服务端限速、支持断点续传）")
    @Auditable(action = "DOWNLOAD", targetType = "FILE", targetIdParam = "nodeId", detail = "流式下载")
    @PreAuthorize("hasAuthority('file:preview') or hasAuthority('file:download') or hasRole('ADMIN')")
    @GetMapping("/{nodeId}/stream")
    public void streamFile(@PathVariable Long nodeId, HttpServletRequest request, HttpServletResponse response) {
        downloadService.streamFile(nodeId, request, response);
    }

    @Operation(summary = "ZIP批量下载")
    @Auditable(action = "DOWNLOAD", targetType = "FILE", detail = "ZIP批量下载")
    @PreAuthorize("hasAuthority('file:download') or hasRole('ADMIN')")
    @PostMapping("/download/zip")
    public void downloadAsZip(@Valid @RequestBody BatchIdsRequest request,
                              HttpServletResponse response) {
        try {
            response.setContentType("application/zip");
            String fileName = URLEncoder.encode("download.zip", StandardCharsets.UTF_8);
            response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            OutputStream os = response.getOutputStream();
            downloadService.downloadAsZip(request.getNodeIds(), os);
            os.flush();
        } catch (Exception e) {
            response.setStatus(500);
        }
    }

    @Operation(summary = "签发短期下载令牌（5分钟有效）")
    @PreAuthorize("hasAuthority('file:download') or hasAuthority('file:preview') or hasRole('ADMIN')")
    @PostMapping("/{nodeId}/download-token")
    public Result<Map<String, String>> issueDownloadToken(@PathVariable Long nodeId) {
        UserContext.CurrentUser user = UserContext.getCurrentUser();
        String token = jwtUtils.generateDownloadToken(
                user.getUserId(), user.getTenantId(), user.getUsername(),
                user.getRoles(), new ArrayList<>(user.getPermissions()), user.getDataScope());
        return Result.success(Map.of("token", token));
    }
}
