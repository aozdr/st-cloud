package com.stcloud.core.service.impl.upload;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.core.entity.FileChunk;
import com.stcloud.core.enums.FileChunkStatus;
import com.stcloud.core.mapper.FileChunkMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分片记录管理器（TASK-002）：封装 file_chunk 的创建/查询/状态流转/清理。
 * 状态语义：0-待上传 1-已上传 2-已合并；合并完成后保留记录（标记 2）以支撑重复 merge 幂等。
 */
@Component
public class UploadChunkManager {

    @Resource
    private FileChunkMapper fileChunkMapper;

    /** 初始化上传时批量创建分片记录（状态 0-待上传） */
    public void createChunkRecords(String uploadId, Long fileNodeId, int totalChunks, Long chunkSize, Long originalSize) {
        for (int i = 1; i <= totalChunks; i++) {
            FileChunk chunk = new FileChunk();
            chunk.setUploadId(uploadId);
            chunk.setFileNodeId(fileNodeId);
            chunk.setChunkIndex(i);
            chunk.setChunkSize(chunkSize);
            chunk.setOriginalSize(originalSize);
            // 新建分片记录状态为待上传
            chunk.setStatus(FileChunkStatus.PENDING.getCode());
            fileChunkMapper.insert(chunk);
        }
    }

    /** 取该 uploadId 的首条分片（用于定位 fileNodeId 与是否替换上传） */
    public FileChunk getFirstChunk(String uploadId) {
        return fileChunkMapper.selectOne(new LambdaQueryWrapper<FileChunk>()
                .eq(FileChunk::getUploadId, uploadId)
                .last("LIMIT 1"));
    }

    /** 取指定分片（断点续传/确认/签发 URL 用） */
    public FileChunk getChunk(String uploadId, int chunkIndex) {
        return fileChunkMapper.selectOne(new LambdaQueryWrapper<FileChunk>()
                .eq(FileChunk::getUploadId, uploadId)
                .eq(FileChunk::getChunkIndex, chunkIndex));
    }

    /** 标记某分片已上传（0->1），返回是否本次新完成（幂等） */
    public boolean markChunkUploaded(String uploadId, int chunkIndex) {
        return fileChunkMapper.markChunkUploaded(uploadId, chunkIndex) > 0;
    }

    /** 合并完成后标记全部分片为已合并(2)，保留记录以支撑重复 merge 幂等 */
    public void markChunksMerged(String uploadId) {
        fileChunkMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FileChunk>()
                .eq(FileChunk::getUploadId, uploadId)
                .lt(FileChunk::getStatus, FileChunkStatus.MERGED.getCode())
                .set(FileChunk::getStatus, FileChunkStatus.MERGED.getCode()));
    }

    /** 中止/失败清理时删除该 uploadId 全部分片记录 */
    public void deleteByUploadId(String uploadId) {
        fileChunkMapper.deleteByUploadId(uploadId);
    }

    /** 查询该 uploadId 的全部分片（按序号升序） */
    public List<FileChunk> listChunks(String uploadId) {
        return fileChunkMapper.selectList(new LambdaQueryWrapper<FileChunk>()
                .eq(FileChunk::getUploadId, uploadId)
                .orderByAsc(FileChunk::getChunkIndex));
    }

    /** 已上传分片序号（DB 视角，status>=1；断点续传辅助） */
    public List<Integer> listUploadedChunkIndexes(String uploadId) {
        return fileChunkMapper.selectUploadedChunkIndexes(uploadId);
    }
}
