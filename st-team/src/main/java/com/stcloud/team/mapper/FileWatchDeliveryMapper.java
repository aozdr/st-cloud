package com.stcloud.team.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.team.entity.FileWatchDelivery;
import com.stcloud.team.entity.FileWatchDeliveryKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FileWatchDeliveryMapper extends BaseMapper<FileWatchDelivery> {

    /** 全局调度仅取最小 ID/租户，不能在默认租户 1 下扫描。 */
    @InterceptorIgnore(tenantLine = "1")
    @Select("SELECT id, tenant_id FROM file_watch_delivery "
            + "WHERE status IN (0, 2) AND next_retry_at <= CURRENT_TIMESTAMP "
            + "ORDER BY id LIMIT #{limit}")
    List<FileWatchDeliveryKey> selectDueKeys(@Param("limit") int limit);

    /** 单条事务内锁定 delivery；锁定、核权、通知写入和完成状态同一事务提交。 */
    @InterceptorIgnore(tenantLine = "1")
    @Select("SELECT * FROM file_watch_delivery WHERE tenant_id = #{tenantId} AND id = #{id} FOR UPDATE")
    FileWatchDelivery selectByTenantIdForUpdate(@Param("tenantId") Long tenantId, @Param("id") Long id);

    @InterceptorIgnore(tenantLine = "1")
    @Update("UPDATE file_watch_delivery SET status = 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status IN (0, 2) "
            + "AND next_retry_at <= CURRENT_TIMESTAMP")
    int markProcessing(@Param("tenantId") Long tenantId, @Param("id") Long id);

    @InterceptorIgnore(tenantLine = "1")
    @Update("UPDATE file_watch_delivery SET status = 3, updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status = 1")
    int markSent(@Param("tenantId") Long tenantId, @Param("id") Long id);

    @InterceptorIgnore(tenantLine = "1")
    @Update("UPDATE file_watch_delivery SET status = 4, updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status = 1")
    int markSuppressed(@Param("tenantId") Long tenantId, @Param("id") Long id);

    /** 失败补偿在独立短事务中写入退避状态，避免覆盖已成功提交的发送状态。 */
    @InterceptorIgnore(tenantLine = "1")
    @Update("UPDATE file_watch_delivery SET retry_count = #{retryCount}, status = #{status}, "
            + "next_retry_at = #{nextRetryAt}, last_error = #{lastError}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND status IN (0, 1, 2)")
    int recordFailure(@Param("tenantId") Long tenantId, @Param("id") Long id,
                      @Param("retryCount") int retryCount, @Param("status") int status,
                      @Param("nextRetryAt") LocalDateTime nextRetryAt,
                      @Param("lastError") String lastError);
}
