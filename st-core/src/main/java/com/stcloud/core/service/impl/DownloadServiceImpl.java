package com.stcloud.core.service.impl;

import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.ratelimit.SpeedLimitService;
import com.stcloud.common.ratelimit.UserTransferLimiter;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileVersion;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileVersionMapper;
import com.stcloud.core.service.DownloadService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.util.FileNameSanitizer;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class DownloadServiceImpl implements DownloadService {

    @Resource
    private FileNodeMapper fileNodeMapper;
    @Resource
    private FileVersionMapper fileVersionMapper;
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
        // generic 下载必须经过 personal-only 最终校验；团队下载只能从显式团队入口调用。
        FileNode node = fileService.getNodeByIdAndOwner(nodeId);
        if (node.getStatus() != NodeStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
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
        streamFile(nodeId, null, request, response);
    }

    @Override
    public void streamFile(Long nodeId, Long versionId, HttpServletRequest request, HttpServletResponse response) {
        // generic 流式读取同样必须在对象存储访问前通过 personal-only 校验。
        FileNode node = fileService.getNodeByIdAndOwner(nodeId);
        if (node.getStatus() != NodeStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        fileService.validateAccessible(nodeId);
        if (node.isFolder()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "文件夹不支持单文件下载，请使用ZIP下载");
        }
        if (node.getUploadStatus() != UploadStatus.COMPLETED.getCode()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "文件尚未上传完成");
        }
        // 历史版本流：内容与大小取 file_version 记录（版本必须属于该节点），其余校验沿用节点
        String storagePath = node.getStoragePath();
        Long sourceSize = node.getFileSize();
        if (versionId != null) {
            FileVersion version = fileVersionMapper.selectById(versionId);
            if (version == null || !nodeId.equals(version.getFileNodeId())) {
                throw new BusinessException(ResultCode.FILE_NOT_FOUND.getCode(), "版本不存在");
            }
            storagePath = version.getStoragePath();
            sourceSize = version.getFileSize();
        }
        Long userId = UserContext.getUserId();
        long fileSize = sourceSize != null ? sourceSize : 0L;
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
            try (InputStream is = ranged ? storageService.downloadObjectRange(storagePath, start, end)
                                         : storageService.downloadObject(storagePath);
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
        List<ZipPlanEntry> plan = buildZipPlan(nodeIds);
        Long userId = UserContext.getUserId();
        long rateBytes = speedLimitService.resolve().getDownloadSpeedLimit() * 1024L;

        try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
            for (ZipPlanEntry item : plan) {
                if (item.directory()) {
                    zos.putNextEntry(new ZipEntry(item.entryName()));
                    zos.closeEntry();
                    continue;
                }
                zos.putNextEntry(new ZipEntry(item.entryName()));
                try (InputStream is = storageService.downloadObject(item.node().getStoragePath())) {
                    pacedTransfer(is, zos, userId, rateBytes);
                }
                zos.closeEntry();
            }
            zos.finish();
        } catch (IOException e) {
            log.error("ZIP下载失败", e);
            throw new BusinessException(ResultCode.STORAGE_SERVICE_ERROR, "ZIP打包失败");
        }
    }

    @Override
    public void preflightZipDownload(List<Long> nodeIds) {
        buildZipPlan(nodeIds);
    }

    /**
     * ZIP 输出前一次性完成所有根节点权限、scope、状态、条目数和声明大小预检。
     * 预检使用受限子树收集，任何异常都在创建 ZipOutputStream 前抛出，避免半个 ZIP。
     */
    private List<ZipPlanEntry> buildZipPlan(List<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "下载节点不能为空");
        }
        List<ZipPlanEntry> plan = new ArrayList<>();
        Set<Long> visitedNodes = new HashSet<>();
        Set<String> entryNames = new HashSet<>();
        long totalSize = 0L;
        for (Long nodeId : nodeIds) {
            FileNode root = fileService.getNodeByIdAndOwner(nodeId);
            if (!visitedNodes.add(root.getId()) || !root.isNormal()) {
                throw new BusinessException(ResultCode.FORBIDDEN);
            }
            fileService.validateAccessible(root.getId());
            if (root.isFile()) {
                if (root.getUploadStatus() != UploadStatus.COMPLETED.getCode()) {
                    throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "ZIP 中包含未完成文件");
                }
                totalSize = addZipPlanEntry(plan, entryNames, visitedNodes, root,
                        safeZipName(root.getName()), false, totalSize);
                continue;
            }
            if (!root.isFolder()) {
                throw new BusinessException(ResultCode.FILE_NOT_FOUND);
            }
            String rootName = safeZipName(root.getName());
            totalSize = addZipPlanEntry(plan, entryNames, visitedNodes, root,
                    rootName + "/", true, totalSize);
            for (FileNode child : fileService.collectDescendants(root.getId())) {
                if (child.getId() == null || !visitedNodes.add(child.getId())) {
                    throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "目录树存在循环或重复引用");
                }
                if (!samePersonalScope(root, child)) {
                    throw new BusinessException(ResultCode.FORBIDDEN);
                }
                if (!child.isNormal()) {
                    continue;
                }
                String relative = relativePath(root, child);
                String entryName = safeZipName(rootName + (relative.isEmpty() ? "" : "/" + relative));
                boolean directory = child.isFolder();
                if (directory) {
                    entryName = entryName.endsWith("/") ? entryName : entryName + "/";
                } else if (child.getUploadStatus() != UploadStatus.COMPLETED.getCode()) {
                    throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "ZIP 中包含未完成文件");
                }
                totalSize = addZipPlanEntry(plan, entryNames, visitedNodes, child,
                        entryName, directory, totalSize);
            }
        }
        return plan;
    }

    private long addZipPlanEntry(List<ZipPlanEntry> plan, Set<String> entryNames, Set<Long> visitedNodes,
                                 FileNode node, String entryName, boolean directory, long totalSize) {
        if (!entryNames.add(entryName)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "ZIP 中存在重复条目");
        }
        if (entryNames.size() > 100_000 || visitedNodes.size() > 500_000) {
            throw new BusinessException(ResultCode.FILE_TOO_LARGE.getCode(), "ZIP 条目数量超过限制");
        }
        long fileSize = node.getFileSize() == null ? 0L : node.getFileSize();
        if (fileSize < 0 || (!directory && totalSize > MAX_ZIP_DOWNLOAD_SIZE - fileSize)) {
            throw new BusinessException(ResultCode.FILE_TOO_LARGE.getCode(),
                    "ZIP下载总量超过限制(500MB)");
        }
        long nextTotal = directory || node.getFileSize() == null ? totalSize : totalSize + fileSize;
        if (nextTotal > MAX_ZIP_DOWNLOAD_SIZE) {
            throw new BusinessException(ResultCode.FILE_TOO_LARGE.getCode(),
                    "ZIP下载总量超过限制(500MB)");
        }
        plan.add(new ZipPlanEntry(node, entryName, directory));
        return nextTotal;
    }

    private boolean samePersonalScope(FileNode root, FileNode child) {
        return root.getTenantId() != null && root.getTenantId().equals(child.getTenantId())
                && root.getOwnerId() != null && root.getOwnerId().equals(child.getOwnerId())
                && (child.getSpaceId() == null || child.getSpaceId() <= 0)
                && (root.getSpaceId() == null || root.getSpaceId() <= 0);
    }

    private String relativePath(FileNode root, FileNode child) {
        String rootPath = normalizePath(root.getPath());
        String childPath = normalizePath(child.getPath());
        if (rootPath.isEmpty()) {
            return childPath;
        }
        if (!childPath.equals(rootPath) && !childPath.startsWith(rootPath + "/")) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "文件路径与目录树不一致");
        }
        return childPath.equals(rootPath) ? "" : childPath.substring(rootPath.length() + 1);
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        String value = path.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private String safeZipName(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "ZIP 条目名称不能为空");
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "ZIP 条目路径非法");
        }
        String[] parts = normalized.split("/", -1);
        boolean trailingSlash = normalized.endsWith("/");
        StringBuilder safe = new StringBuilder(normalized.length());
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() && !(trailingSlash && i == parts.length - 1)) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "ZIP 条目存在空路径段");
            }
            if (part.equals("..") || part.equals(".")) {
                throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "ZIP 条目存在路径穿越");
            }
            if (!part.isEmpty()) {
                String safePart = FileNameSanitizer.sanitize(part);
                if (safePart == null) {
                    throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "ZIP 条目名称非法");
                }
                if (safe.length() > 0) safe.append('/');
                safe.append(safePart);
            }
        }
        if (trailingSlash) safe.append('/');
        return safe.toString();
    }

    private record ZipPlanEntry(FileNode node, String entryName, boolean directory) {
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
