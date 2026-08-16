package com.stcloud.core.controller;

import com.stcloud.common.response.Result;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.context.TenantContext;
import com.stcloud.core.service.ArchiveService;
import com.stcloud.core.service.ArchiveProgressReporter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    /** 解压后台线程池：避免长解压占用 Web 请求线程 */
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    private static final class ArchiveTask {
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger count = new AtomicInteger();
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean failed = new AtomicBoolean();
        volatile String error;
    }

    /** 前端轮询的进度快照 */
    public record ArchiveProgressVO(String status, int total, int done, String error, int count) {
    }

    @Operation(summary = "浏览压缩包内容列表")
    @GetMapping("/{nodeId}/archive/contents")
    public Result<List<Map<String, Object>>> listArchiveContents(@PathVariable Long nodeId) {
        return Result.success(archiveService.listArchiveContents(nodeId));
    }

    @Operation(summary = "解压文件到指定目录（异步任务，返回 taskId 供轮询进度）")
    @PostMapping("/{nodeId}/archive/extract")
    public Result<Map<String, Object>> extractArchive(
            @PathVariable Long nodeId,
            @RequestParam(defaultValue = "0") Long targetFolderId) {
        String taskId = UUID.randomUUID().toString();
        ArchiveTask task = new ArchiveTask();
        TASKS.put(taskId, task);
        // 顺带清理已完成任务，避免内存表无限增长
        if (TASKS.size() > 200) {
            TASKS.entrySet().removeIf(e -> e.getValue().finished.get() || e.getValue().failed.get());
        }
        // 后台线程无请求 ThreadLocal：在请求线程捕获用户/租户上下文，工作线程内恢复，避免权限校验 403 与租户过滤失效
        UserContext.CurrentUser currentUser = UserContext.getCurrentUser();
        Long tenantId = TenantContext.getTenantId();
        String tenantMode = TenantContext.getTenantMode();
        EXECUTOR.execute(() -> {
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
            }
        });
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
        String status = task.finished.get() ? "finished" : task.failed.get() ? "failed" : "running";
        return Result.success(new ArchiveProgressVO(status, task.total.get(), task.done.get(), task.error, task.count.get()));
    }
}
