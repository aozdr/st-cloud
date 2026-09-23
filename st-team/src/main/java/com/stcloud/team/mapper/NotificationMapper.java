package com.stcloud.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.team.entity.Notification;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    /** 推送铃铛状态时显式按租户和用户计数，异步线程不依赖默认租户上下文。 */
    @InterceptorIgnore(tenantLine = "1")
    @Select("SELECT COUNT(*) FROM notification WHERE tenant_id = #{tenantId} "
            + "AND user_id = #{userId} AND `read` = 0")
    long countUnread(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    /** 新版关注通知的幂等查找，显式使用持久化租户条件。 */
    @InterceptorIgnore(tenantLine = "1")
    @Select("SELECT * FROM notification WHERE tenant_id = #{tenantId} AND user_id = #{userId} "
            + "AND event_id = #{eventId} LIMIT 1")
    Notification findByTenantUserEvent(@Param("tenantId") Long tenantId,
                                       @Param("userId") Long userId,
                                       @Param("eventId") Long eventId);
}
