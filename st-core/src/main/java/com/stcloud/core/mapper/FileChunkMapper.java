package com.stcloud.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.core.entity.FileChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface FileChunkMapper extends BaseMapper<FileChunk> {

    /**
     * 查询已上传的分片序号（断点续传）
     * status >= 1 表示已上传或已合并
     */
    // SQL 状态含义：status >= 1 已上传(1)或已合并(2)
    @Select("SELECT chunk_index FROM file_chunk WHERE upload_id = #{uploadId} AND status >= 1 ORDER BY chunk_index")
    List<Integer> selectUploadedChunkIndexes(@Param("uploadId") String uploadId);

    /**
     * 根据uploadId删除所有分片记录
     */
    @Delete("DELETE FROM file_chunk WHERE upload_id = #{uploadId}")
    int deleteByUploadId(@Param("uploadId") String uploadId);
    /**
     * 标记分片已上传（TASK-002 状态落库）：仅当当前为待上传(0)时置为已上传(1)。
     * 返回影响行数：1=本次由待上传转为已上传；0=已上传过（幂等，调用方可跳过重复处理）。
     */
    // SQL 状态含义：status = 0 待上传 -> 1 已上传（幂等守卫）
    @Update("UPDATE file_chunk SET status = 1, updated_at = NOW() WHERE upload_id = #{uploadId} AND chunk_index = #{chunkIndex} AND status = 0")
    int markChunkUploaded(@Param("uploadId") String uploadId, @Param("chunkIndex") int chunkIndex);
}
