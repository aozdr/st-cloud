package com.stcloud.core.service;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * S3 存储抽象层
 */
public interface StorageService {

    /**
     * 简单上传：直接上传流到主bucket
     *
     * @param key          对象key
     * @param inputStream  输入流
     * @param size         内容大小
     * @param contentType  MIME类型
     */
    void uploadObject(String key, InputStream inputStream, long size, String contentType);

    /**
     * 生成预签名下载URL（有效期1小时）
     *
     * @param storagePath 对象key
     * @return 预签名URL
     */
    String generateDownloadUrl(String storagePath);

    /**
     * 删除S3对象
     *
     * @param storagePath 对象key
     */
    void deleteObject(String storagePath);

    /**
     * 从S3下载文件流
     *
     * @param storagePath 对象key
     * @return 输入流
     */
    InputStream downloadObject(String storagePath);

    /**
     * 从S3按字节范围下载文件流（支持断点续传）
     *
     * @param storagePath 对象key
     * @param start       起始字节（含）
     * @param end         结束字节（含）
     * @return 输入流
     */
    InputStream downloadObjectRange(String storagePath, long start, long end);

    /**
     * 分片上传：初始化，返回S3 multipart uploadId
     *
     * @param key 对象key
     * @return S3 uploadId
     */
    String initMultipartUpload(String key);

    /**
     * 分片上传：为指定part生成预签名URL（前端直传S3）
     *
     * @param key         对象key
     * @param s3UploadId  S3 multipart uploadId
     * @param partNumber  分片序号（从1开始）
     * @param expiry      URL有效期
     * @return 预签名URL
     */
    String presignUploadPart(String key, String s3UploadId, int partNumber, Duration expiry);

    /**
     * 分片上传：合并所有分片（内部通过ListParts获取ETag）
     *
     * @param key         对象key
     * @param s3UploadId  S3 multipart uploadId
     */
    void completeMultipartUpload(String key, String s3UploadId);

    /**
     * 分片上传：中止（清理已上传分片）
     *
     * @param key         对象key
     * @param s3UploadId  S3 multipart uploadId
     */
    void abortMultipartUpload(String key, String s3UploadId);

    /**
     * 分片上传：查询已上传到S3的分片序号列表（用于断点续传）
     *
     * @param key         对象key
     * @param s3UploadId  S3 multipart uploadId
     * @return 已上传分片序号列表（从1开始）
     */
    List<Integer> listUploadedParts(String key, String s3UploadId);
}