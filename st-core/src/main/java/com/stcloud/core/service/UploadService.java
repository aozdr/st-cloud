package com.stcloud.core.service;

import com.stcloud.core.dto.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传服务
 */
public interface UploadService {

    /**
     * 秒传检查：根据 MD5 判断文件是否已存在
     */
    UploadCheckResponse checkInstantUpload(UploadCheckRequest request);

    /**
     * 简单上传（小文件直接上传）
     */
    FileNodeVO simpleUpload(Long parentId, MultipartFile file, Long spaceId);

    /**
     * 初始化分片上传，返回预签名 URL 列表
     */
    UploadInitResponse initChunkedUpload(UploadInitRequest request);

    /**
     * 查询分片上传进度
     */
    UploadStatusResponse getUploadStatus(String uploadId, String s3UploadId);

    /**
     * 获取分片上传预签名URL（限速门控，按分片逐个签发）
     */
    ChunkUrlResponse getChunkUrl(String uploadId, String s3UploadId, int chunkIndex, Integer clientLimit);

    /**
     * 确认分片上传完成（释放限速配额）
     */
    void confirmChunk(String uploadId, String s3UploadId, int chunkIndex);

    /**
     * 合并分片，完成上传
     */
    FileNodeVO mergeChunks(UploadMergeRequest request);

    /**
     * 中止分片上传，清理已上传分片
     */
    void abortUpload(String uploadId, String s3UploadId, Long fileId);
}
