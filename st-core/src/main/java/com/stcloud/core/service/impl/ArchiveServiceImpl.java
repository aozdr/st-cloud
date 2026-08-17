package com.stcloud.core.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeType;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.StorageInfoVO;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.ArchiveService;
import com.stcloud.core.service.ArchiveProgressReporter;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.impl.upload.UploadCommitManager;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.stcloud.common.context.TenantContext;

/**
 * 在线解压服务实现：使用 JDK 内置 ZipInputStream 读取 ZIP 压缩包内容
 */
@Slf4j
@Service
public class ArchiveServiceImpl implements ArchiveService {

    @Resource
    private FileNodeMapper fileNodeMapper;
    @Resource
    private StorageService storageService;
    @Resource
    private FileObjectService fileObjectService;
    @Resource
    private UserQuotaMapper userQuotaMapper;
    @Resource
    private UploadCommitManager uploadCommitManager;
    @Resource
    private UploadStorageManager uploadStorageManager;

    private static final String ZIP_SUFFIX = "zip";

    @Override
    public List<Map<String, Object>> listArchiveContents(Long nodeId) {
        FileNode node = getAccessibleFileNode(nodeId);
        validateZipFile(node);

        List<Map<String, Object>> entries = new ArrayList<>();
        // 从 S3 下载压缩包并逐条读取 ZIP 条目
        try (InputStream s3Stream = storageService.downloadObject(node.getStoragePath());
             java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(s3Stream)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // 跳过 macOS 系统文件
                if (entry.getName().startsWith("__MACOSX") || entry.getName().endsWith(".DS_Store")) {
                    zis.closeEntry();
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.getName());
                item.put("size", entry.getSize());
                item.put("isDirectory", entry.isDirectory());
                String[] parts = entry.getName().split("/");
                item.put("fileName", parts[parts.length - 1]);
                entries.add(item);
                zis.closeEntry();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("读取压缩包内容失败, nodeId={}", nodeId, e);
            throw new BusinessException(ResultCode.BUSINESS_ERROR);
        }
        return entries;
    }

    @Override
    public int extractArchive(Long nodeId, Long targetFolderId) {
        return extractArchive(nodeId, targetFolderId, null);
    }

    /**
     * 带进度回调的解压（控制器异步任务使用；reporter 为 null 时与无回调行为一致）。
     * <p>
     * 事务边界（F5）：ZIP 下载到本地临时文件（事务外）→ 预检统计 → 逐条目去重预查 + S3 上传（事务外）
     * → 一个事务内插入文件夹/文件节点 + 扣配额 + 引用校正（UploadCommitManager.commitExtract）；
     * 失败时尽力清理本次上传对象，残留交由定时任务兜底。
     */
    @Override
    public int extractArchive(Long nodeId, Long targetFolderId, ArchiveProgressReporter reporter) {
        FileNode node = getAccessibleFileNode(nodeId);
        validateZipFile(node);
        validateTargetFolder(targetFolderId);

        Long userId = UserContext.getUserId();
        Long tenantId = TenantContext.getTenantId();

        // 解压产物 path 前缀：目标目录路径（0=根目录）
        String targetFolderPath = "/";
        if (targetFolderId != null && targetFolderId != 0L) {
            FileNode targetFolder = fileNodeMapper.selectById(targetFolderId);
            if (targetFolder != null && targetFolder.getPath() != null && !targetFolder.getPath().isEmpty()) {
                targetFolderPath = targetFolder.getPath();
            }
        }

        // 事务外：ZIP 先下载到本地临时文件，后续统计与逐条目上传均不占用 DB 连接
        Path tempZip = downloadZipToTemp(node);
        try {
            // 预检：先统计压缩包内文件条目总大小与总数，校验配额，避免解压中途超配额留下孤儿 S3 对象
            ArchiveSummary summary = summarizeArchive(tempZip);
            checkUserQuota(userId, summary.totalSize);
            if (reporter != null) reporter.begin(summary.totalFiles);

            // 事务外：逐条目读取并上传 S3（去重预查），收集待落库条目元数据
            List<Map<String, Object>> entries = new ArrayList<>();
            try (java.util.zip.ZipInputStream zis =
                         new java.util.zip.ZipInputStream(Files.newInputStream(tempZip))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().startsWith("__MACOSX") || entry.getName().endsWith(".DS_Store")) {
                        zis.closeEntry();
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("zipPath", entry.getName());
                    item.put("directory", entry.isDirectory());
                    if (!entry.isDirectory()) {
                        // 读取条目内容（整块入内存，用于 MD5 计算与上传）
                        byte[] content = readEntryContent(zis);
                        String[] parts = entry.getName().split("/");
                        String fileName = parts[parts.length - 1];
                        String suffix = getSuffix(fileName);
                        String md5 = DigestUtil.md5Hex(content);
                        String contentType = guessContentType(suffix);

                        // 去重预查（事务外）：同租户同 md5 复用物理对象（秒传），否则上传到规范路径 {tenantId}/{md5}
                        FileObject existing = fileObjectService.findByTenantAndMd5(tenantId, md5);
                        String storagePath;
                        boolean uploadedNew = false;
                        if (existing != null) {
                            storagePath = existing.getStoragePath();
                        } else {
                            storagePath = tenantId + "/" + md5;
                            storageService.uploadObject(storagePath,
                                    new ByteArrayInputStream(content),
                                    content.length, contentType);
                            uploadedNew = true;
                        }
                        item.put("size", (long) content.length);
                        item.put("md5", md5);
                        item.put("storagePath", storagePath);
                        item.put("contentType", contentType);
                        item.put("suffix", suffix);
                        item.put("uploadedNew", uploadedNew);
                    }
                    entries.add(item);
                    zis.closeEntry();
                }
            } catch (BusinessException e) {
                // 上传阶段失败：尽力清理本次已上传对象，残留交由定时任务兜底
                cleanupUploadedEntries(tenantId, entries);
                throw e;
            } catch (Exception e) {
                log.error("解压失败, nodeId={}", nodeId, e);
                cleanupUploadedEntries(tenantId, entries);
                throw new BusinessException(ResultCode.BUSINESS_ERROR);
            }

            // 一个事务内落库：文件夹/文件节点 + 原子扣减配额 + 引用校正
            int count;
            try {
                count = uploadCommitManager.commitExtract(userId, tenantId, targetFolderId,
                        targetFolderPath, entries);
            } catch (RuntimeException e) {
                // 事务失败清理：仅删除本次新上传且无记录/引用归零的对象；残留交由定时任务兜底
                cleanupUploadedEntries(tenantId, entries);
                throw e;
            }
            // 进度回调在全部落库成功后按文件数触发（语义与改造前一致：成功才计数）
            for (int i = 0; i < count; i++) {
                if (reporter != null) reporter.onFileExtracted();
            }
            return count;
        } finally {
            try {
                Files.deleteIfExists(tempZip);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程
            }
        }
    }

    /** ZIP 内文件条目统计（跳过 macOS 系统文件；流式读取保证 size 准确） */
    private static final class ArchiveSummary {
        long totalSize;
        int totalFiles;
    }

    private ArchiveSummary summarizeArchive(Path zipFile) {
        ArchiveSummary summary = new ArchiveSummary();
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(Files.newInputStream(zipFile))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()
                        && !entry.getName().startsWith("__MACOSX")
                        && !entry.getName().endsWith(".DS_Store")) {
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        summary.totalSize += len;
                    }
                    summary.totalFiles++;
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            log.error("统计压缩包大小失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.BUSINESS_ERROR);
        }
        return summary;
    }

    /** 事务外：将 S3 上的压缩包下载到本地临时文件（不占用 DB 连接） */
    private Path downloadZipToTemp(FileNode node) {
        try {
            Path temp = Files.createTempFile("archive-extract-", ".zip");
            try (InputStream in = storageService.downloadObject(node.getStoragePath());
                 OutputStream out = Files.newOutputStream(temp)) {
                in.transferTo(out);
            }
            return temp;
        } catch (IOException e) {
            log.error("下载压缩包到临时文件失败, nodeId={}", node.getId(), e);
            throw new BusinessException(ResultCode.BUSINESS_ERROR);
        }
    }

    /** 读取 ZIP 条目完整内容（整块入内存，用于 MD5 计算与上传） */
    private byte[] readEntryContent(java.util.zip.ZipInputStream zis) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = zis.read(buffer)) > 0) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }

    /** 解压失败时尽力清理本次新上传且无记录/引用归零的对象（残留交由定时任务兜底） */
    private void cleanupUploadedEntries(Long tenantId, List<Map<String, Object>> entries) {
        for (Map<String, Object> item : entries) {
            if (Boolean.TRUE.equals(item.get("uploadedNew"))) {
                cleanupOrphanUpload(tenantId, (String) item.get("md5"), (String) item.get("storagePath"));
            }
        }
    }

    /**
     * 孤儿对象清理（与简单上传口径一致）：仅当当前无对象记录（本次 insertIgnore 已随事务回滚）
     * 或记录引用归零且路径一致时才删除物理对象；删除失败不阻断主流程，交由定时任务兜底。
     */
    private void cleanupOrphanUpload(Long tenantId, String md5, String storagePath) {
        try {
            FileObject current = fileObjectService.findByTenantAndMd5(tenantId, md5);
            boolean noRecord = current == null;
            boolean unreferenced = current != null
                    && current.getRefCount() != null && current.getRefCount() <= 0
                    && storagePath.equals(current.getStoragePath());
            if (noRecord || unreferenced) {
                uploadStorageManager.deleteObjectQuietly(storagePath);
                log.warn("已尽力清理解压失败产生的孤儿对象: md5={}, storagePath={}", md5, storagePath);
            }
        } catch (Exception e) {
            // 清理失败不阻断主流程，交由定时任务兜底
            log.warn("解压失败清理孤儿对象异常（交由定时任务兜底）: md5={}", md5, e);
        }
    }

    /** 校验个人存储配额（与上传/复制路径一致） */
    private void checkUserQuota(Long userId, long fileSize) {
        StorageInfoVO quota = userQuotaMapper.getUserQuota(userId);
        if (quota != null && quota.getQuota() != null && quota.getQuota() > 0) {
            long used = quota.getUsed() == null ? 0 : quota.getUsed();
            if (used + fileSize > quota.getQuota()) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
            }
        }
    }

    /** 校验解压目标目录：0=根目录；否则必须存在、是文件夹、且属于当前用户（或租户管理员） */
    private void validateTargetFolder(Long targetFolderId) {
        if (targetFolderId == null || targetFolderId == 0L) return;
        FileNode folder = fileNodeMapper.selectById(targetFolderId);
        if (folder == null || !folder.isNormal()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        if (folder.getNodeType() != NodeType.FOLDER.getCode()) {
            throw new BusinessException(ResultCode.FILE_TYPE_NOT_ALLOWED);
        }
        Long userId = UserContext.getUserId();
        if (!folder.getOwnerId().equals(userId) && !UserContext.canAccessTenant()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    /** 获取文件节点并校验访问权限 */
    private FileNode getAccessibleFileNode(Long nodeId) {
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || !node.isNormal()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        Long userId = UserContext.getUserId();
        if (!node.getOwnerId().equals(userId) && !UserContext.canAccessTenant()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return node;
    }

    /** 校验文件为 ZIP 格式 */
    private void validateZipFile(FileNode node) {
        if (node.getNodeType() != NodeType.FILE.getCode()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR);
        }
        String suffix = node.getSuffix();
        if (suffix == null || !ZIP_SUFFIX.equalsIgnoreCase(suffix)) {
            throw new BusinessException(ResultCode.FILE_TYPE_NOT_ALLOWED);
        }
    }

    private String getSuffix(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx >= 0 ? fileName.substring(idx + 1).toLowerCase() : null;
    }

    private String guessContentType(String suffix) {
        if (suffix == null) return "application/octet-stream";
        return switch (suffix.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain";
            case "mp4" -> "video/mp4";
            case "mp3" -> "audio/mpeg";
            default -> "application/octet-stream";
        };
    }
}
