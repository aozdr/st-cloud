package com.stcloud.core.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.StorageInfoVO;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.ArchiveService;
import com.stcloud.core.service.ArchiveProgressReporter;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.StorageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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

    private static final String ZIP_SUFFIX = "zip";
    /** 引用计数：文件夹不引用物理对象 */
    private static final int REF_COUNT_NONE = 0;
    /** 引用计数：文件初始引用（随后由 syncRefCountByMd5 校正为同 md5 节点数） */
    private static final int REF_COUNT_INITIAL = 1;

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
    @Transactional
    public int extractArchive(Long nodeId, Long targetFolderId) {
        return extractArchive(nodeId, targetFolderId, null);
    }

    /** 带进度回调的解压（控制器异步任务使用；reporter 为 null 时与无回调行为一致） */
    @Override
    @Transactional
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

        // 预检：先统计压缩包内文件条目总大小与总数，校验配额，避免解压中途超配额留下孤儿 S3 对象
        ArchiveSummary summary = summarizeArchive(node);
        checkUserQuota(userId, summary.totalSize);
        if (reporter != null) reporter.begin(summary.totalFiles);

        int count = 0;
        try (InputStream s3Stream = storageService.downloadObject(node.getStoragePath());
             java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(s3Stream)) {
            // ZIP 内路径 -> file_node ID 的映射，用于构建嵌套文件夹结构
            Map<String, Long> folderMap = new HashMap<>();
            folderMap.put("", targetFolderId);
            // ZIP 内路径 -> file_node path 的映射，用于产物路径拼接
            Map<String, String> folderPathMap = new HashMap<>();
            folderPathMap.put("", targetFolderPath);

            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().startsWith("__MACOSX") || entry.getName().endsWith(".DS_Store")) {
                    zis.closeEntry();
                    continue;
                }

                String[] parts = entry.getName().split("/");
                // 逐级创建父文件夹
                String parentZipPath = "";
                for (int i = 0; i < parts.length - 1; i++) {
                    String folderZipPath = parentZipPath.isEmpty() ? parts[i] : parentZipPath + "/" + parts[i];
                    if (!folderMap.containsKey(folderZipPath)) {
                        Long parentFolderId = folderMap.get(parentZipPath);
                        String parentPath = folderPathMap.get(parentZipPath);
                        String newPath = "/".equals(parentPath) ? "/" + parts[i] : parentPath + "/" + parts[i];
                        Long newFolderId = createFolderNode(parts[i], parentFolderId, newPath, userId, tenantId);
                        folderMap.put(folderZipPath, newFolderId);
                        folderPathMap.put(folderZipPath, newPath);
                    }
                    parentZipPath = folderZipPath;
                }

                if (!entry.isDirectory()) {
                    Long parentFolderId = folderMap.getOrDefault(parentZipPath, targetFolderId);
                    String parentPath = folderPathMap.getOrDefault(parentZipPath, targetFolderPath);
                    String fileName = parts[parts.length - 1];
                    String suffix = getSuffix(fileName);
                    String filePath = "/".equals(parentPath) ? "/" + fileName : parentPath + "/" + fileName;

                    // 读取条目内容（整块入内存，用于 MD5 计算与上传）
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    byte[] content = baos.toByteArray();

                    // 原子扣减配额（并发超配额时 UPDATE 返回 0，抛异常回滚本次事务；预检保证常规场景不触发）
                    if (content.length > 0 && userQuotaMapper.updateStorageUsed(userId, (long) content.length) <= 0) {
                        throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
                    }

                    // 走去重引用：同租户同 md5 复用物理对象（秒传），否则上传到规范路径 {tenantId}/{md5}
                    String md5 = DigestUtil.md5Hex(content);
                    FileObject object = fileObjectService.acquire(tenantId, md5, content.length,
                            () -> {
                                String key = tenantId + "/" + md5;
                                storageService.uploadObject(key,
                                        new ByteArrayInputStream(content),
                                        content.length,
                                        guessContentType(suffix));
                                return key;
                            });
                    if (object == null) {
                        throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
                    }

                    createFileNode(fileName, suffix, object.getStoragePath(), (long) content.length,
                            md5, object.getId(),
                            parentFolderId, filePath, userId, tenantId);
                    // 保持 file_node.ref_count 与对象引用一致（同 md5 节点数）
                    fileNodeMapper.syncRefCountByMd5(md5);
                    count++;
                    if (reporter != null) reporter.onFileExtracted();
                }
                zis.closeEntry();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("解压失败, nodeId={}", nodeId, e);
            throw new BusinessException(ResultCode.BUSINESS_ERROR);
        }
        return count;
    }

    /** ZIP 内文件条目统计（跳过 macOS 系统文件；流式读取保证 size 准确） */
    private static final class ArchiveSummary {
        long totalSize;
        int totalFiles;
    }

    private ArchiveSummary summarizeArchive(FileNode node) {
        ArchiveSummary summary = new ArchiveSummary();
        try (InputStream s3Stream = storageService.downloadObject(node.getStoragePath());
             java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(s3Stream)) {
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
            log.error("统计压缩包大小失败, nodeId={}", node.getId(), e);
            throw new BusinessException(ResultCode.BUSINESS_ERROR);
        }
        return summary;
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

    private Long createFolderNode(String name, Long parentId, String path, Long userId, Long tenantId) {
        FileNode folder = new FileNode();
        folder.setTenantId(tenantId);
        folder.setParentId(parentId);
        folder.setNodeType(NodeType.FOLDER.getCode());
        folder.setName(name);
        folder.setPath(path);
        folder.setStatus(NodeStatus.NORMAL.getCode());
        folder.setOwnerId(userId);
        folder.setUploaderId(userId);
        // 解压创建的文件夹不引用任何物理对象
        folder.setRefCount(REF_COUNT_NONE);
        folder.setVersion(0);
        fileNodeMapper.insert(folder);
        return folder.getId();
    }

    private void createFileNode(String name, String suffix, String storagePath, Long size,
                                String fileMd5, Long objectId,
                                Long parentId, String path, Long userId, Long tenantId) {
        FileNode file = new FileNode();
        file.setTenantId(tenantId);
        file.setParentId(parentId);
        file.setNodeType(NodeType.FILE.getCode());
        file.setName(name);
        file.setPath(path);
        file.setFileSize(size);
        file.setFileMd5(fileMd5);
        file.setObjectId(objectId);
        file.setSuffix(suffix);
        file.setContentType(guessContentType(suffix));
        file.setStoragePath(storagePath);
        file.setStatus(NodeStatus.NORMAL.getCode());
        file.setUploadStatus(UploadStatus.COMPLETED.getCode());
        file.setOwnerId(userId);
        file.setUploaderId(userId);
        // 引用计数由 syncRefCountByMd5 校正为同 md5 节点数
        file.setRefCount(REF_COUNT_INITIAL);
        file.setVersion(0);
        fileNodeMapper.insert(file);
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
