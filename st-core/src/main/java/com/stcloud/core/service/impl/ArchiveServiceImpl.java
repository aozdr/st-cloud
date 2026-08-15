package com.stcloud.core.service.impl;

import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.ArchiveService;
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

    private static final String ZIP_SUFFIX = "zip";
    /** 引用计数：解压产物未关联 file_object 去重对象（直接存储路径），无引用 */
    private static final int REF_COUNT_NONE = 0;

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
        FileNode node = getAccessibleFileNode(nodeId);
        validateZipFile(node);

        Long userId = UserContext.getUserId();
        Long tenantId = TenantContext.getTenantId();

        int count = 0;
        try (InputStream s3Stream = storageService.downloadObject(node.getStoragePath());
             java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(s3Stream)) {
            // ZIP 内路径 -> file_node ID 的映射，用于构建嵌套文件夹结构
            Map<String, Long> folderMap = new HashMap<>();
            folderMap.put("", targetFolderId);

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
                        Long newFolderId = createFolderNode(parts[i], parentFolderId, userId, tenantId);
                        folderMap.put(folderZipPath, newFolderId);
                    }
                    parentZipPath = folderZipPath;
                }

                if (!entry.isDirectory()) {
                    Long parentFolderId = folderMap.getOrDefault(parentZipPath, targetFolderId);
                    String fileName = parts[parts.length - 1];
                    String suffix = getSuffix(fileName);
                    String storagePath = "files/" + tenantId + "/" + UUID.randomUUID() + "/" + fileName;

                    // 读取条目内容并上传到 S3
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    byte[] content = baos.toByteArray();

                    storageService.uploadObject(storagePath,
                            new ByteArrayInputStream(content),
                            content.length,
                            guessContentType(suffix));

                    createFileNode(fileName, suffix, storagePath, (long) content.length,
                            parentFolderId, userId, tenantId);
                    count++;
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

    private Long createFolderNode(String name, Long parentId, Long userId, Long tenantId) {
        FileNode folder = new FileNode();
        folder.setTenantId(tenantId);
        folder.setParentId(parentId);
        folder.setNodeType(NodeType.FOLDER.getCode());
        folder.setName(name);
        folder.setPath("/");
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
                                Long parentId, Long userId, Long tenantId) {
        FileNode file = new FileNode();
        file.setTenantId(tenantId);
        file.setParentId(parentId);
        file.setNodeType(NodeType.FILE.getCode());
        file.setName(name);
        file.setPath("/");
        file.setFileSize(size);
        file.setSuffix(suffix);
        file.setContentType(guessContentType(suffix));
        file.setStoragePath(storagePath);
        file.setStatus(NodeStatus.NORMAL.getCode());
        file.setUploadStatus(UploadStatus.COMPLETED.getCode());
        file.setOwnerId(userId);
        file.setUploaderId(userId);
        // 解压创建的文件直接落 S3，未走 file_object 去重，引用计数为 0
        file.setRefCount(REF_COUNT_NONE);
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
