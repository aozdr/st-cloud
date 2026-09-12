package com.stcloud.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.core.entity.UploadSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;

@Mapper
public interface UploadSessionMapper extends BaseMapper<UploadSession> {
    /** 会话状态是上传操作的权威锁；只有预期状态仍有效的请求可以认领副作用。 */
    @Update("<script>UPDATE upload_session SET status = #{targetStatus}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status IN "
            + "<foreach collection='expectedStatuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>"
            + "</script>")
    int transitionStatus(@Param("id") Long id,
                         @Param("expectedStatuses") Collection<Integer> expectedStatuses,
                         @Param("targetStatus") Integer targetStatus);
}
