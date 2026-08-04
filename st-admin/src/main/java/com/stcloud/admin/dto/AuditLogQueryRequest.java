package com.stcloud.admin.dto;

import lombok.Data;

/**
 * 审计日志多条件查询请求
 */
@Data
public class AuditLogQueryRequest {
    /** 用户名（模糊） */
    private String username;
    /** 操作类型 */
    private String action;
    /** 目标类型 */
    private String targetType;
    /** 目标名称（模糊） */
    private String targetName;
    /** 状态：1成功 0失败 */
    private Integer status;
    /** 关键词（模糊搜索 detail + targetName） */
    private String keyword;
    /** IP 地址（模糊） */
    private String ipAddress;
    /** 起始时间（yyyy-MM-dd HH:mm:ss） */
    private String startTime;
    /** 结束时间（yyyy-MM-dd HH:mm:ss） */
    private String endTime;
    /** 排序方式：desc=降序(默认), asc=升序 */
    private String sort = "desc";
    /** 页码（从1开始） */
    private Integer page = 1;
    /** 每页条数 */
    private Integer size = 20;
}
