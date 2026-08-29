package com.stcloud.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.ratelimit.SpeedLimitService;
import com.stcloud.common.ratelimit.UserTransferLimiter;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.DownloadService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class DownloadServiceImpl implements DownloadService {

    @Resource
    private FileNodeMapper fileNodeMapper;
    @Resource
    private StorageService storageService;
    @Resource
    private FileService fileService;
    @Resource
    private SpeedLimitService speedLimitService;
    @Resource
    private UserTransferLimiter userTransferLimiter;

    private static final long MAX_ZIP_DOWNLOAD_SIZE = 500 * 1024 * 1024L; // 500MB

    @Override
    public String generateDownloadUrl(Long nodeId) {
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || node.getStatus() != NodeStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        // 团队空间文件：检查 spaceId 归属由 TeamController 权限控制
        // 个人文件：仅属主可访问（单一租户，不因 dataScope 放行他人文件）
        if (node.getSpaceId() == null || node.getSpaceId() <= 0) {
            if (!UserContext.getUserId().equals(node.getOwnerId())) {
                throw new BusinessException(ResultCode.PERMISSION_DENIED);
            }
        }
        if (node.isFolder()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "文件夹不支持单文件下载，请使用ZIP下载");
        }
        if (node.getUploadStatus() != UploadStatus.COMPLETED.getCode()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "文件尚未上传完成");
        }
        return storageService.generateDownloadUrl(node.getStoragePath());
    }

    @Override
    public void streamFile(Long nodeId, HttpServletRequest request, HttpServletResponse response) {
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || node.getStatus() != NodeStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        fileService.validateAccessible(nodeId);
        // 团队空间文件：检查 spaceId 归属由 TeamController 权限控制
        // 个人文件：仅属主可访问（单一租户，不因 dataScope 放行他人文件）
        if (node.getSpaceId() == null || node.getSpaceId() <= 0) {
            if (!UserContext.getUserId().equals(node.getOwnerId())) {
                throw new BusinessException(ResultCode.PERMISSION_DENIED);
            }
        }
        if (node.isFolder()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "文件夹不支持单文件下载，请使用ZIP下载");
        }
        if (node.getUploadStatus() != UploadStatus.COMPLETED.getCode()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "文件尚未上传完成");
        }
        Long userId = UserContext.getUserId();
        long fileSize = node.getFileSize() != null ? node.getFileSize() : 0L;
        long start = 0L;
        long end = fileSize > 0 ? fileSize - 1 : 0L;
        boolean ranged = false;
        String rangeHeader = request.getHeader("Range");
        if (fileSize > 0 && StringUtils.hasText(rangeHeader) && rangeHeader.startsWith("bytes=")) {
            String spec = rangeHeader.substring(6).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash > 0) {
                    start = Long.parseLong(spec.substring(0, dash).trim());
                    String tail = spec.substring(dash + 1).trim();
                    end = tail.isEmpty() ? fileSize - 1 : Long.parseLong(tail);
                } else if (dash == 0) {
                    // suffix range: bytes=-N (last N bytes)
                    long suffix = Long.parseLong(spec.substring(1).trim());
                    start = Math.max(0, fileSize - suffix);
                    end = fileSize - 1;
                }
                if (start <= end && start < fileSize) {
                    ranged = true;
                    if (end >= fileSize) {
                        end = fileSize - 1;
                    }
                } else {
                    start = 0;
                    end = fileSize - 1;
                }
            } catch (NumberFormatException ex) {
                start = 0;
                end = fileSize - 1;
            }
        }
        boolean inline = "1".equals(request.getParameter("inline"));
        // 空文件（0 字节）：响应体长度为 0；否则按 range 计算
        long contentLength = fileSize == 0 ? 0 : end - start + 1;
        try {
            response.setContentType(node.getContentType() != null ? node.getContentType() : "application/octet-stream");
            String fileName = URLEncoder.encode(node.getName(), StandardCharsets.UTF_8).replace("+", "%20");
            response.setHeader("Content-Disposition",
                    (inline ? "inline" : "attachment") + "; filename=\"" + fileName + "\"; filename*=UTF-8''" + fileName);
            response.setHeader("Accept-Ranges", "bytes");
            if (ranged) {
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
            }
            response.setContentLengthLong(contentLength);
            // 空文件：不访问对象存储，直接返回空响应
            // （S3 空对象 GET 可能挂起，且声明长度与实际不一致会导致客户端无限等待）
            if (fileSize == 0) {
                response.flushBuffer();
                return;
            }
            long rateBytes = SpeedLimitService.capRate(speedLimitService.resolve().getDownloadSpeedLimit(), parseClientLimit(request.getParameter("clientLimit"))) * 1024L;
            try (InputStream is = ranged ? storageService.downloadObjectRange(node.getStoragePath(), start, end)
                                         : storageService.downloadObject(node.getStoragePath());
                 OutputStream os = response.getOutputStream()) {
                pacedTransfer(is, os, userId, rateBytes);
                os.flush();
            }
        } catch (IOException e) {
            log.error("文件流式下载失败: nodeId={}", nodeId, e);
            throw new BusinessException(ResultCode.STORAGE_SERVICE_ERROR, "文件下载失败");
        }
    }

    /**
     * 按用户级共享令牌桶节奏化传输下载字节，rateBytesPerSec<=0 时不限速。
     * 多个并发下载共享同一用户的令牌桶，保证聚合速率不超上限。
     */
    private void pacedTransfer(InputStream is, OutputStream os, Long userId, long rateBytesPerSec) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = is.read(buffer)) != -1) {
            userTransferLimiter.acquireDownload(userId, n, rateBytesPerSec);
            os.write(buffer, 0, n);
        }
    }

    @Override
    public void downloadAsZip(List<Long> nodeIds, OutputStream outputStream) {
        Long userId = UserContext.getUserId();
        long rateBytes = speedLimitService.resolve().getDownloadSpeedLimit() * 1024L;
        long totalSize = 0;

        try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
            for (Long nodeId : nodeIds) {
                fileService.validateAccessible(nodeId);
                FileNode node = fileService.getNodeByIdAndOwner(nodeId);
                if (node.isFolder()) {
                    totalSize += addFolderToZip(node, "", zos, userId, rateBytes);
                } else {
                    if (node.getUploadStatus() != UploadStatus.COMPLETED.getCode()) {
                        continue;
                    }
                    totalSize += addFileToZip(node, node.getName(), zos, userId, rateBytes);
                }
                if (totalSize > MAX_ZIP_DOWNLOAD_SIZE) {
                    throw new BusinessException(ResultCode.FILE_TOO_LARGE.getCode(),
                            "ZIP下载总量超过限制(500MB)");
                }
            }
            zos.finish();
        } catch (IOException e) {
            log.error("ZIP下载失败", e);
            throw new BusinessException(ResultCode.STORAGE_SERVICE_ERROR, "ZIP打包失败");
        }
    }

    private long addFolderToZip(FileNode folder, String zipPath, ZipOutputStream zos, Long userId, long rateBytes) {
        long totalSize = 0;
        String currentZipPath = zipPath.isEmpty() ? folder.getName() : zipPath + "/" + folder.getName();
        try {
            zos.putNextEntry(new ZipEntry(currentZipPath + "/"));
            zos.closeEntry();
        } catch (IOException e) {
            log.error("添加ZIP条目失败: {}", currentZipPath, e);
        }

        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, folder.getId())
                .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode())
                // 个人文件夹仅属主的子文件；团队文件夹包含团队成员文件（团队鉴权前置）
                .eq(folder.getSpaceId() == null || folder.getSpaceId() <= 0, FileNode::getOwnerId, userId);
        List<FileNode> children = fileNodeMapper.selectList(wrapper);
        for (FileNode child : children) {
            if (child.isFolder()) {
                totalSize += addFolderToZip(child, currentZipPath, zos, userId, rateBytes);
            } else if (child.getUploadStatus() == UploadStatus.COMPLETED.getCode()) {
                totalSize += addFileToZip(child, currentZipPath + "/" + child.getName(), zos, userId, rateBytes);
            }
        }
        return totalSize;
    }

    private long addFileToZip(FileNode node, String zipEntryName, ZipOutputStream zos, Long userId, long rateBytes) {
        try {
            zos.putNextEntry(new ZipEntry(zipEntryName));
            try (InputStream is = storageService.downloadObject(node.getStoragePath())) {
                pacedTransfer(is, zos, userId, rateBytes);
            }
            zos.closeEntry();
            return node.getFileSize() != null ? node.getFileSize() : 0;
        } catch (IOException e) {
            log.error("添加文件到ZIP失败: {}", zipEntryName, e);
            return 0;
        }
    }

    private Integer parseClientLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
