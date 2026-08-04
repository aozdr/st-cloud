package com.stcloud.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.core.entity.FileChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FileChunkMapper extends BaseMapper<FileChunk> {

    /**
     * 查询已上传的分片序号（断点续传）
     * status >= 1 表示已上传或已合并
     */
    @Select("SELECT chunk_index FROM file_chunk WHERE upload_id = #{uploadId} AND status >= 1 ORDER BY chunk_index")
    List<Integer> selectUploadedChunkIndexes(@Param("uploadId") String uploadId);

    /**
     * 根据uploadId删除所有分片记录
     */
    @Delete("DELETE FROM file_chunk WHERE upload_id = #{uploadId}")
    int deleteByUploadId(@Param("uploadId") String uploadId);
}
