package com.stcloud.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.core.entity.EventLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件 Outbox Mapper（TASK-004）。
 */
@Mapper
public interface EventLogMapper extends BaseMapper<EventLog> {

    /** 标记已投递 */
    // SQL 状态含义：status = 1 已投递；status <> 1 幂等守卫，避免重复置为已投递
    @Update("UPDATE event_log SET status = 1, processed_at = NOW() WHERE id = #{id} AND status <> 1")
    int markSent(@Param("id") Long id);

    /** 标记投递失败并累计重试次数 */
    // SQL 状态含义：status = 2 投递失败，retry_count 累计重试次数
    @Update("UPDATE event_log SET status = 2, retry_count = retry_count + 1 WHERE id = #{id}")
    int markFailed(@Param("id") Long id);

    /** 查询待重投的失败事件（未达最大重试次数，按创建时间升序） */
    // SQL 状态含义：status = 2 失败且未达最大重试次数，等待重投
    @Select("SELECT * FROM event_log WHERE status = 2 AND retry_count < #{maxRetry} AND deleted = 0 ORDER BY created_at LIMIT #{limit}")
    List<EventLog> selectRetryable(@Param("maxRetry") int maxRetry, @Param("limit") int limit);

    /**
     * 清理超过保留期的历史事件（TASK-002）：仅删除可安全清除的行。
     * - status=1（已投递，含本地兜底标记）：processed_at 超过 cutoff → 删除
     * - status=2 且 retry_count 达上限（重试耗尽）：created_at 超过 cutoff → 删除
     * - status=0（在途）与 status=2 未耗尽（仍可重试）永不清理
     */
    @Delete("DELETE FROM event_log WHERE "
            + "(status = 1 AND processed_at IS NOT NULL AND processed_at < #{cutoff}) "
            + "OR (status = 2 AND retry_count >= #{maxRetry} AND created_at < #{cutoff})")
    int cleanupExpired(@Param("cutoff") LocalDateTime cutoff, @Param("maxRetry") int maxRetry);
}
