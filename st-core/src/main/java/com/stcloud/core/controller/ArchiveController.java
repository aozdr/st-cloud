package com.stcloud.core.controller;

import com.stcloud.common.response.Result;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.context.TenantContext;
import com.stcloud.core.service.ArchiveService;
import com.stcloud.core.service.ArchiveProgressReporter;
import com.stcloud.core.config.ArchiveSafetyProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在线解压：支持 ZIP 格式压缩包的在线浏览与解压
 */
@Tag(name = "在线解压", description = "压缩包在线浏览与解压")
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ArchiveController {

    @Resource
    private ArchiveService archiveService;

    /** 解压任务进度表（内存态；任务完成后由前端轮询收尾，顺带清理） */
    private static final Map<String, ArchiveTask> TASKS = new ConcurrentHashMap<>();
    @Resource
    private ArchiveSafetyProperties archiveSafetyProperties;
    /** 解压后台线程池：避免长解压占用 Web 请求线程；队列大小由服务端配置约束。 */
    private ExecutorService executor;
    private static final Map<Long, AtomicInteger> ACTIVE_BY_USER = new ConcurrentHashMap<>();

    @PostConstruct
    void initExecutor() {
        int queueSize = Math.max(1, archiveSafetyProperties.getMaxQueueSize());
        executor = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueSize),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static final class ArchiveTask {
        final String taskId;
        final Long ownerId;
        final Long tenantId;
        final Long nodeId;
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger count = new AtomicInteger();
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean failed = new AtomicBoolean();
        volatile String error;

        ArchiveTask(String taskId, Long ownerId, Long tenantId, Long nodeId) {
            this.taskId = taskId;
            this.ownerId = ownerId;
            this.tenantId = tenantId;
            this.nodeId = nodeId;
        }
    }

    /** 前端轮询的进度快照 */
    public record ArchiveProgressVO(String status, int total, int done, String error, int count) {
    }

    @Operation(summary = "浏览压缩包内容列表")
    @PreAuthorize("hasAuthority('file:preview') or hasRole('ADMIN')")
    @GetMapping("/{nodeId}/archive/contents")
    public Result<List<Map<String, Object>>> listArchiveContents(@PathVariable Long nodeId) {
        return Result.success(archiveService.listArchiveContents(nodeId));
    }

    @Operation(summary = "解压文件到指定目录（异步任务，返回 taskId 供轮询进度）")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/{nodeId}/archive/extract")
    public Result<Map<String, Object>> extractArchive(
            @PathVariable Long nodeId,
            @RequestParam(defaultValue = "0") Long targetFolderId) {
        String taskId = UUID.randomUUID().toString();
        Long ownerId = UserContext.getUserId();
        Long tenantId = TenantContext.getTenantId();
        AtomicInteger active = ACTIVE_BY_USER.computeIfAbsent(ownerId, ignored -> new AtomicInteger());
        if (active.incrementAndGet() > archiveSafetyProperties.getMaxActiveTasksPerUser()) {
            active.decrementAndGet();
            throw new BusinessException(com.stcloud.common.response.ResultCode.BUSINESS_ERROR.getCode(),
                    "同一用户的解压任务超过并发限制");
        }
        ArchiveTask task = new ArchiveTask(taskId, ownerId, tenantId, nodeId);
        TASKS.put(taskId, task);
        // 顺带清理已完成任务，避免内存表无限增长
        if (TASKS.size() > 200) {
            TASKS.entrySet().removeIf(e -> e.getValue().finished.get() || e.getValue().failed.get());
        }
        // 后台线程无请求 ThreadLocal：在请求线程捕获用户/租户上下文，工作线程内恢复，避免权限校验 403 与租户过滤失效
        UserContext.CurrentUser currentUser = UserContext.getCurrentUser();
        String tenantMode = TenantContext.getTenantMode();
        try {
            executor.execute(() -> {
            UserContext.setCurrentUser(currentUser);
            TenantContext.setTenantId(tenantId);
            TenantContext.setTenantMode(tenantMode);
            try {
                int count = archiveService.extractArchive(nodeId, targetFolderId, new ArchiveProgressReporter() {
                    @Override
                    public void begin(int total) {
                        task.total.set(total);
                    }

                    @Override
                    public void onFileExtracted() {
                        task.done.incrementAndGet();
                    }
                });
                task.count.set(count);
                task.finished.set(true);
            } catch (Exception e) {
                task.error = e instanceof BusinessException ? e.getMessage() : "解压失败";
                task.failed.set(true);
            } finally {
                UserContext.clear();
                TenantContext.clear();
                if (active.decrementAndGet() == 0) {
                    ACTIVE_BY_USER.remove(ownerId, active);
                }
            }
            });
        } catch (RejectedExecutionException e) {
            TASKS.remove(taskId);
            if (active.decrementAndGet() == 0) {
                ACTIVE_BY_USER.remove(ownerId, active);
            }
            throw new BusinessException(com.stcloud.common.response.ResultCode.BUSINESS_ERROR.getCode(),
                    "解压任务队列已满，请稍后重试");
        }
        return Result.success(Map.of("taskId", taskId));
    }

    @Operation(summary = "查询解压任务进度")
    @GetMapping("/{nodeId}/archive/progress/{taskId}")
    public Result<ArchiveProgressVO> archiveProgress(
            @PathVariable Long nodeId,
            @PathVariable String taskId) {
        ArchiveTask task = TASKS.get(taskId);
        if (task == null) {
            return Result.success(new ArchiveProgressVO("missing", 0, 0, null, 0));
        }
        if (!java.util.Objects.equals(task.ownerId, UserContext.getUserId())
                || !java.util.Objects.equals(task.tenantId, TenantContext.getTenantId())
                || !java.util.Objects.equals(task.nodeId, nodeId)) {
            throw new BusinessException(com.stcloud.common.response.ResultCode.FORBIDDEN);
        }
        String status = task.finished.get() ? "finished" : task.failed.get() ? "failed" : "running";
        return Result.success(new ArchiveProgressVO(status, task.total.get(), task.done.get(), task.error, task.count.get()));
    }
}
