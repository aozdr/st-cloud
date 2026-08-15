package com.stcloud.core.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.annotation.Auditable;
import com.stcloud.common.response.Result;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.utils.JwtUtils;
import com.stcloud.core.dto.*;
import com.stcloud.core.convert.FileConvertService;
import com.stcloud.core.service.DownloadService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.UploadService;
import com.stcloud.core.text.TextFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
    private final FileConvertService fileConvertService;
    private final TextFileService textFileService;

    // ==================== 目录管理 ====================

    @Operation(summary = "文件格式转换（Word<->PDF）")
    @Auditable(action = "CONVERT_FILE", targetType = "FILE")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/{nodeId}/convert")
    public Result<FileNodeVO> convertFile(@PathVariable Long nodeId,
                                          @Valid @RequestBody ConvertFileRequest request) {
        return Result.success(fileConvertService.convert(nodeId, request.getFileName()));
    }

    @Operation(summary = "保存文本文件内容（个人）")
    @Auditable(action = "EDIT_TEXT_FILE", targetType = "FILE")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PutMapping("/{nodeId}/text-content")
    public Result<Void> saveTextContent(@PathVariable Long nodeId,
                                        @Valid @RequestBody TextContentRequest request) {
        // 个人文件：owner 校验（管理员直通）；团队文件走团队接口
        fileService.getNodeByIdAndOwner(nodeId);
        textFileService.overwriteContent(nodeId, request.getContent().getBytes(StandardCharsets.UTF_8));
        return Result.success();
    }

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

    @Operation(summary = "中转上传：接收一个小块（限速 pacing）")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/upload/relay-chunk")
    public Result<RelayChunkResponse> relayChunk(
            @RequestParam String uploadId,
            @RequestParam String s3UploadId,
            @RequestParam int seq,
            HttpServletRequest request) throws IOException {
        // Content-Length 由服务层按会话 relayChunkSize 校验，防超大请求
        long contentLength = request.getContentLengthLong();
        return Result.success(uploadService.relayChunk(uploadId, s3UploadId, seq,
                request.getInputStream(), contentLength));
    }

    @Operation(summary = "中转上传：完成末片并合并")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/upload/relay-finalize")
    public Result<FileNodeVO> relayFinalize(
            @RequestParam String uploadId,
            @RequestParam String s3UploadId) {
        return Result.success(uploadService.relayFinalize(uploadId, s3UploadId));
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
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        }
    }

    @Operation(summary = "签发短期下载令牌（5分钟有效）")
    @PreAuthorize("hasAuthority('file:download') or hasAuthority('file:preview') or hasRole('ADMIN')")
    @PostMapping("/{nodeId}/download-token")
    public Result<Map<String, String>> issueDownloadToken(@PathVariable Long nodeId) {
        UserContext.CurrentUser user = UserContext.getCurrentUser();
        String token = jwtUtils.generateDownloadToken(
                user.getUserId(), user.getTenantId(), user.getUsername(),
                user.getRoles(), new ArrayList<>(user.getPermissions()), user.getDataScope(),
                nodeId);
        return Result.success(Map.of("token", token));
    }

    @Operation(summary = "存储空间按类型统计")
    @GetMapping("/storage/by-type")
    public Result<List<Map<String, Object>>> storageByType() {
        return Result.success(fileService.storageByType());
    }

    @Operation(summary = "重复文件检测")
    @GetMapping("/duplicates")
    public Result<List<Map<String, Object>>> findDuplicates() {
        return Result.success(fileService.findDuplicates());
    }

    @Operation(summary = "隐藏文件/文件夹")
    @PutMapping("/{nodeId}/hide")
    public Result<Void> hideFile(@PathVariable Long nodeId) {
        fileService.setHidden(nodeId, true);
        return Result.success(null);
    }

    @Operation(summary = "取消隐藏")
    @PutMapping("/{nodeId}/unhide")
    public Result<Void> unhideFile(@PathVariable Long nodeId) {
        fileService.setHidden(nodeId, false);
        return Result.success(null);
    }

    @Operation(summary = "隐藏文件列表")
    @GetMapping("/hidden")
    public Result<List<FileNodeVO>> listHidden() {
        return Result.success(fileService.listHidden());
    }

    @Operation(summary = "重复文件详情列表（同 MD5 的文件）")
    @GetMapping("/duplicates/detail")
    public Result<List<FileNodeVO>> duplicateDetail(@RequestParam String md5) {
        return Result.success(fileService.findDuplicateDetail(md5));
    }

    @Operation(summary = "文件历史版本数量")
    @GetMapping("/{nodeId}/versions/count")
    public Result<Integer> versionCount(@PathVariable Long nodeId) {
        return Result.success(fileService.versionCount(nodeId));
    }

    @Operation(summary = "清理重复文件（保留最早创建的，其余移入回收站）")
    @PostMapping("/duplicates/cleanup")
    public Result<Map<String, Object>> cleanupDuplicates(@RequestParam String md5) {
        return Result.success(fileService.cleanupDuplicates(md5));
    }
}
