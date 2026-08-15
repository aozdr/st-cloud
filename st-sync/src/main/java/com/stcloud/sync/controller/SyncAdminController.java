package com.stcloud.sync.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.response.Result;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.RecycleBinService;
import com.stcloud.sync.entity.SyncChangeLog;
import com.stcloud.sync.entity.SyncRoot;
import com.stcloud.sync.mapper.SyncChangeLogMapper;
import com.stcloud.sync.mapper.SyncRootMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同步异常数据清理（管理员）。
 * <p>
 * 背景（20260815-sync-refactor）：旧版 keep_both 冲突处理会把冲突副本回流上传，
 * 产生大量机器格式垃圾副本（xxx (本地-20260815141555).zip / xxx (冲突-...).zip）。
 * 本接口按格式匹配这些副本，先清理指向它们的同步变更日志，
 * 再复用 RecycleBinService.permanentDeleteAdmin 永久删除：
 * 引用计数归零后删除 S3 物理对象、退还配额、清理 ES 索引。
 */
@Tag(name = "同步管理", description = "同步异常数据清理")
@RestController
@RequestMapping("/api/admin/sync")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SyncAdminController {

    private final SyncRootMapper syncRootMapper;
    private final FileNodeMapper fileNodeMapper;
    private final SyncChangeLogMapper syncChangeLogMapper;
    private final RecycleBinService recycleBinService;

    /** 机器生成的冲突副本名：`xxx (本地-20260815141555).zip` / `xxx (冲突-20260815141555-1).zip` */
    private static final String JUNK_NAME_RE = ".*\\((本地|冲突)-\\d{14}(-\\d+)?\\)(\\.[^/\\\\]+)?";

    @Operation(summary = "清理同步生成的冲突副本（数据库 + S3 物理对象）")
    @PostMapping("/cleanup-junk")
    public Result<Map<String, Object>> cleanupJunk() {
        List<SyncRoot> roots = syncRootMapper.selectList(null);
        List<Long> junkIds = new ArrayList<>();
        int scannedRoots = 0;
        int skippedFolders = 0;

        for (SyncRoot root : roots) {
            if (root.getCloudFolderNodeId() == null) {
                continue;
            }
            FileNode folder = fileNodeMapper.selectById(root.getCloudFolderNodeId());
            if (folder == null) {
                continue;
            }
            scannedRoots++;
            String prefix = folder.getPath().endsWith("/") ? folder.getPath() : folder.getPath() + "/";
            List<FileNode> nodes = fileNodeMapper.selectList(
                    new LambdaQueryWrapper<FileNode>()
                            .likeRight(FileNode::getPath, prefix));
            for (FileNode node : nodes) {
                if (node.isFolder()) {
                    skippedFolders++;
                    continue;
                }
                if (node.getName() != null && node.getName().matches(JUNK_NAME_RE)) {
                    junkIds.add(node.getId());
                }
            }
        }

        int deleted = junkIds.size();
        if (!junkIds.isEmpty()) {
            // 先清指向垃圾节点的变更日志，避免客户端收到已删除节点的 delta
            syncChangeLogMapper.delete(
                    new LambdaQueryWrapper<SyncChangeLog>().in(SyncChangeLog::getFileNodeId, junkIds));
            // 永久删除：S3 物理对象（引用归零时）+ 配额退还 + ES 索引清理
            recycleBinService.permanentDeleteAdmin(junkIds);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scannedRoots", scannedRoots);
        report.put("foundJunkNodes", deleted);
        report.put("skippedFolders", skippedFolders);
        return Result.success(report);
    }
}
