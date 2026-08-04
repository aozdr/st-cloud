package com.stcloud.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stcloud.admin.dto.AuditLogQueryRequest;
import com.stcloud.admin.entity.AuditLog;
import com.stcloud.admin.mapper.AuditLogMapper;
import com.stcloud.common.response.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Tag(name = "审计日志", description = "操作审计日志查询")
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:audit:view') or hasRole('ADMIN')")
public class AuditLogController {

    private final AuditLogMapper auditLogMapper;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Operation(summary = "审计日志列表（多条件筛选/分页）")
    @GetMapping("/list")
    public Result<IPage<AuditLog>> listAuditLogs(@ModelAttribute AuditLogQueryRequest request) {
        Page<AuditLog> pageParam = new Page<>(request.getPage(), request.getSize());
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();

        // 用户名（模糊）
        wrapper.like(StringUtils.hasText(request.getUsername()),
                AuditLog::getUsername, request.getUsername());

        // 操作类型（精确）
        wrapper.eq(StringUtils.hasText(request.getAction()),
                AuditLog::getAction, request.getAction());

        // 目标类型（精确）
        wrapper.eq(StringUtils.hasText(request.getTargetType()),
                AuditLog::getTargetType, request.getTargetType());

        // 目标名称（模糊）
        wrapper.like(StringUtils.hasText(request.getTargetName()),
                AuditLog::getTargetName, request.getTargetName());

        // 状态（精确）
        wrapper.eq(request.getStatus() != null,
                AuditLog::getStatus, request.getStatus());

        // IP 地址（模糊）
        wrapper.like(StringUtils.hasText(request.getIpAddress()),
                AuditLog::getIpAddress, request.getIpAddress());

        // 时间范围
        LocalDateTime start = parseDateTime(request.getStartTime(), true);
        LocalDateTime end = parseDateTime(request.getEndTime(), false);
        wrapper.ge(start != null, AuditLog::getCreatedAt, start);
        wrapper.le(end != null, AuditLog::getCreatedAt, end);

        // 关键词（同时搜索 detail 和 targetName）
        if (StringUtils.hasText(request.getKeyword())) {
            String kw = request.getKeyword();
            wrapper.and(w -> w.like(AuditLog::getDetail, kw)
                    .or().like(AuditLog::getTargetName, kw)
                    .or().like(AuditLog::getUsername, kw)
                    .or().like(AuditLog::getIpAddress, kw));
        }

        wrapper.orderBy(true, "asc".equalsIgnoreCase(request.getSort()), AuditLog::getCreatedAt);

        return Result.success(auditLogMapper.selectPage(pageParam, wrapper));
    }

    /**
     * 解析时间字符串，失败返回 null。
     * @param startOfDay true=取当天起始 00:00:00；false=取当天结束 23:59:59
     */
    private LocalDateTime parseDateTime(String dateTimeStr, boolean startOfDay) {
        if (!StringUtils.hasText(dateTimeStr)) return null;
        try {
            // 完整日期时间
            if (dateTimeStr.length() >= 19) {
                return LocalDateTime.parse(dateTimeStr, DT_FMT);
            }
            // 仅日期 yyyy-MM-dd
            if (dateTimeStr.length() >= 10) {
                LocalDate date = LocalDate.parse(dateTimeStr.substring(0, 10));
                return startOfDay ? date.atStartOfDay() : date.atTime(23, 59, 59);
            }
        } catch (Exception e) {
            // 忽略解析失败
        }
        return null;
    }
}
