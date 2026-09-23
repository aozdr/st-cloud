package com.stcloud.team.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.response.Result;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.team.dto.NotificationVO;
import com.stcloud.team.dto.NotificationTargetVO;
import com.stcloud.team.entity.Notification;
import com.stcloud.team.mapper.NotificationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.event.NotificationUnreadChangedEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "通知", description = "站内通知管理")
@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationMapper notificationMapper;
    private final com.stcloud.team.service.FileWatchNotificationService fileWatchNotificationService;
    private final ApplicationEventPublisher eventPublisher;

    @Operation(summary = "未读通知数")
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        Long userId = UserContext.getUserId();
        Long tenantId = UserContext.getTenantId();
        Long count = notificationMapper.countUnread(tenantId, userId);
        return Result.success(count);
    }

    @Operation(summary = "通知列表")
    @GetMapping
    public Result<IPage<NotificationVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "通知分页参数不合法");
        }
        Long userId = UserContext.getUserId();
        Long tenantId = UserContext.getTenantId();
        Page<Notification> pageParam = new Page<>(page, size);
        IPage<Notification> notifPage = notificationMapper.selectPage(pageParam,
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getTenantId, tenantId)
                        .eq(Notification::getUserId, userId)
                        .orderByDesc(Notification::getCreatedAt));
        IPage<NotificationVO> voPage = notifPage.convert(n ->
                fileWatchNotificationService.toVO(n, tenantId, userId));
        return Result.success(voPage);
    }

    @Operation(summary = "标记单条已读")
    @PutMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        Long tenantId = UserContext.getTenantId();
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getTenantId, tenantId)
                .eq(Notification::getUserId, userId)
                .set(Notification::getRead, 1));
        // 已读状态提交后同步到同账号的其他在线设备，防止铃铛回弹。
        eventPublisher.publishEvent(new NotificationUnreadChangedEvent(tenantId, userId));
        return Result.success();
    }

    @Operation(summary = "全部已读")
    @PutMapping("/read-all")
    public Result<Void> markAllRead() {
        Long userId = UserContext.getUserId();
        Long tenantId = UserContext.getTenantId();
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getTenantId, tenantId)
                .eq(Notification::getUserId, userId)
                .eq(Notification::getRead, 0)
                .set(Notification::getRead, 1));
        eventPublisher.publishEvent(new NotificationUnreadChangedEvent(tenantId, userId));
        return Result.success();
    }

    @Operation(summary = "解析通知安全目标")
    @GetMapping("/{id}/target")
    public Result<NotificationTargetVO> target(@PathVariable Long id) {
        return Result.success(fileWatchNotificationService.target(
                UserContext.getTenantId(), UserContext.getUserId(), id));
    }
}
