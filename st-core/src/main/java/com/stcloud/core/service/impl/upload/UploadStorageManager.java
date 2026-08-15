package com.stcloud.core.service.impl.upload;

import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.StorageService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * 上传存储管理器（TASK-002）：封装 S3 分片生命周期（init/presign/complete/abort/list）
 * 与对象上传/删除、云盘容量检查，供 UploadServiceImpl 编排调用。
 */
@Component
public class UploadStorageManager {

    @Resource
    private StorageService storageService;

    @Resource
    private CloudStorageService cloudStorageService;

    /** 云盘总容量检查（配额之外的物理容量层） */
    public void checkCapacity(long delta) {
        cloudStorageService.checkCapacity(delta);
    }

    /** 初始化 S3 分片上传，返回 S3 uploadId */
    public String initMultipart(String key) {
        return storageService.initMultipartUpload(key);
    }

    /** 为指定分片生成预签名上传 URL */
    public String presignPart(String key, String s3UploadId, int partNumber, Duration expiry) {
        return storageService.presignUploadPart(key, s3UploadId, partNumber, expiry);
    }

    /** 服务端直接写入一个 part（中转模式使用） */
    public void uploadPart(String key, String s3UploadId, int partNumber, java.io.InputStream inputStream, long size) {
        storageService.uploadPart(key, s3UploadId, partNumber, inputStream, size);
    }

    /** 合并全部分片 */
    public void completeMultipart(String key, String s3UploadId) {
        storageService.completeMultipartUpload(key, s3UploadId);
    }

    /** 中止分片上传，清理 S3 已传分片 */
    public void abortMultipart(String key, String s3UploadId) {
        storageService.abortMultipartUpload(key, s3UploadId);
    }

    /** 查询 S3 实际已上传分片序号（断点续传权威来源） */
    public List<Integer> listUploadedParts(String key, String s3UploadId) {
        return storageService.listUploadedParts(key, s3UploadId);
    }

    /** 简单上传对象（StorageService 实现内自行处理存储异常） */
    public void uploadObject(String key, InputStream inputStream, long size, String contentType) {
        storageService.uploadObject(key, inputStream, size, contentType);
    }

    /** 删除对象（尽力而为，失败不阻断主流程） */
    public void deleteObjectQuietly(String storagePath) {
        if (storagePath == null || storagePath.isEmpty()) {
            return;
        }
        try {
            storageService.deleteObject(storagePath);
        } catch (Exception ignored) {
            // 尽力清理：失败仅记录，不影响主流程
        }
    }
}