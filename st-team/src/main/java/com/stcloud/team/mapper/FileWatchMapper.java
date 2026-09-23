package com.stcloud.team.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stcloud.team.entity.FileWatch;
import com.stcloud.team.entity.FileWatchMatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface FileWatchMapper extends BaseMapper<FileWatch> {

    /**
     * 按事件所属租户/空间匹配直接订阅。显式忽略租户拦截器是为了支持事务外全局调度之外的
     * 异步事件仍严格使用 event tenant；SQL 已同时带 tenant_id 与 space_id 条件。
     */
    @InterceptorIgnore(tenantLine = "1")
    @Select("<script>SELECT w.id, w.tenant_id, w.user_id, w.node_id "
            + "FROM file_watch w JOIN file_node n ON n.id = w.node_id "
            + "AND n.tenant_id = w.tenant_id AND n.deleted = 0 "
            + "WHERE w.tenant_id = #{tenantId} AND n.tenant_id = #{tenantId} "
            + "AND ((#{spaceId} IS NOT NULL AND #{spaceId} &gt; 0 AND n.space_id = #{spaceId}) "
            + "OR ((#{spaceId} IS NULL OR #{spaceId} &lt;= 0) AND (n.space_id IS NULL OR n.space_id &lt;= 0))) "
            + "AND w.node_id IN "
            + "<foreach collection='nodeIds' item='nodeId' open='(' separator=',' close=')'>#{nodeId}</foreach>"
            + "</script>")
    List<FileWatchMatch> findMatches(@Param("tenantId") Long tenantId,
                                     @Param("spaceId") Long spaceId,
                                     @Param("nodeIds") Collection<Long> nodeIds);

    /**
     * 目录删除/移动前的廉价预检：没有同租户同空间订阅时不遍历后代树。
     * 查询仍显式限定租户和空间，不能以默认租户 1 作为异步授权兜底。
     */
    @InterceptorIgnore(tenantLine = "1")
    @Select("SELECT CASE WHEN EXISTS (SELECT 1 FROM file_watch w "
            + "JOIN file_node n ON n.id = w.node_id AND n.tenant_id = w.tenant_id AND n.deleted = 0 "
            + "WHERE w.tenant_id = #{tenantId} AND ((#{spaceId} IS NOT NULL AND #{spaceId} > 0 "
            + "AND n.space_id = #{spaceId}) OR ((#{spaceId} IS NULL OR #{spaceId} <= 0) "
            + "AND (n.space_id IS NULL OR n.space_id <= 0)))) THEN 1 ELSE 0 END")
    boolean hasAnyInScope(@Param("tenantId") Long tenantId, @Param("spaceId") Long spaceId);

    /** PUT 幂等竞争下使用当前读，避免 RR 快照看不到并发事务刚提交的订阅。 */
    @InterceptorIgnore(tenantLine = "1")
    @Select("SELECT * FROM file_watch WHERE tenant_id = #{tenantId} AND user_id = #{userId} "
            + "AND node_id = #{nodeId} FOR UPDATE")
    FileWatch selectForUpdate(@Param("tenantId") Long tenantId,
                              @Param("userId") Long userId,
                              @Param("nodeId") Long nodeId);

    /** 处理投递时按订阅代际复核：取消后重新关注产生的新 ID 不会命中旧队列。 */
    @InterceptorIgnore(tenantLine = "1")
    @Select("<script>SELECT * FROM file_watch WHERE tenant_id = #{tenantId} AND user_id = #{userId} "
            + "AND id IN <foreach collection='watchIds' item='watchId' open='(' separator=',' close=')'>#{watchId}</foreach>"
            + "</script>")
    List<FileWatch> findForDelivery(@Param("tenantId") Long tenantId,
                                    @Param("userId") Long userId,
                                    @Param("watchIds") Collection<Long> watchIds);
}
