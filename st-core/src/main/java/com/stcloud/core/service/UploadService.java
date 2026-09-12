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

    /** 团队空间秒传检查：spaceId 必须由显式团队入口提供。 */
    UploadCheckResponse checkTeamInstantUpload(Long spaceId, UploadCheckRequest request);

    /**
     * 简单上传（小文件直接上传）
     */
    FileNodeVO simpleUpload(Long parentId, MultipartFile file, Long spaceId);

    /** 团队空间简单上传。 */
    FileNodeVO simpleTeamUpload(Long spaceId, Long parentId, MultipartFile file);

    /**
     * 初始化分片上传，返回预签名 URL 列表
     */
    UploadInitResponse initChunkedUpload(UploadInitRequest request);

    /** 团队空间分片上传初始化。 */
    UploadInitResponse initTeamChunkedUpload(Long spaceId, UploadInitRequest request);

    /**
     * 查询分片上传进度
     */
    UploadStatusResponse getUploadStatus(String uploadId, String s3UploadId);

    /** 团队空间查询分片状态。 */
    UploadStatusResponse getTeamUploadStatus(Long spaceId, String uploadId, String s3UploadId);

    /**
     * 获取分片上传预签名URL（限速门控，按分片逐个签发）
     */
    ChunkUrlResponse getChunkUrl(String uploadId, String s3UploadId, int chunkIndex, Integer clientLimit);

    /** 团队空间获取分片预签名 URL。 */
    ChunkUrlResponse getTeamChunkUrl(Long spaceId, String uploadId, String s3UploadId,
                                     int chunkIndex, Integer clientLimit);

    /**
     * 确认分片上传完成（释放限速配额）
     */
    void confirmChunk(String uploadId, String s3UploadId, int chunkIndex);

    /** 团队空间确认分片。 */
    void confirmTeamChunk(Long spaceId, String uploadId, String s3UploadId, int chunkIndex);

    /**
     * 合并分片，完成上传
     */
    FileNodeVO mergeChunks(UploadMergeRequest request);

    /** 团队空间合并分片。 */
    FileNodeVO mergeTeamChunks(Long spaceId, UploadMergeRequest request);

    /**
     * 中止分片上传，清理已上传分片
     */
    /**
     * 中转模式接收一个小块（pacing 节流接收 + 缓冲，累积≥5MB 触发 uploadPart）
     */
    RelayChunkResponse relayChunk(String uploadId, String s3UploadId, int seq,
                                  java.io.InputStream inputStream, long chunkBytes);

    /** 团队空间中转上传小块。 */
    RelayChunkResponse relayTeamChunk(Long spaceId, String uploadId, String s3UploadId, int seq,
                                      java.io.InputStream inputStream, long chunkBytes);

    /**
     * 中转模式完成上传（末片 uploadPart + 合并）
     */
    FileNodeVO relayFinalize(String uploadId, String s3UploadId);

    /** 团队空间中转上传完成。 */
    FileNodeVO relayTeamFinalize(Long spaceId, String uploadId, String s3UploadId);

    void abortUpload(String uploadId, String s3UploadId, Long fileId);

    /** 团队空间中止分片上传。 */
    void abortTeamUpload(Long spaceId, String uploadId, String s3UploadId, Long fileId);

    /** 返回当前用户拥有且属于指定团队空间的上传会话节点，用于 Controller ACL 前置校验。 */
    Long getOwnedUploadSessionNodeId(Long spaceId, String uploadId);
}
