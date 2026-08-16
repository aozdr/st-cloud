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

    /** 查询待重投事件：投递失败(status=2)未达上限，或异步投递前崩溃遗留的 PENDING(status=0) 超时行 */
    // SQL 状态含义：status = 2 失败未达上限等待重投；status = 0 在途但 updated_at 早于 stuckBefore 视为卡死
    @Select("SELECT * FROM event_log WHERE deleted = 0 AND retry_count < #{maxRetry} "
            + "AND (status = 2 OR (status = 0 AND updated_at < #{stuckBefore})) "
            + "ORDER BY created_at LIMIT #{limit}")
    List<EventLog> selectRetryable(@Param("maxRetry") int maxRetry, @Param("limit") int limit,
                                   @Param("stuckBefore") LocalDateTime stuckBefore);

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
