package com.stcloud.team.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.task.TenantTaskRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 文件锁过期自动释放定时任务：每小时检查并释放过期的文件锁。
 * lock_expire_at 早于当前时间的锁定视为过期，自动清除锁定字段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileLockExpireTask {

    private final FileNodeMapper fileNodeMapper;
    private final TenantTaskRunner tenantTaskRunner;

    @Scheduled(cron = "0 0 * * * ?")
    public void releaseExpiredLocks() {
        tenantTaskRunner.runForEachTenant("文件锁过期清理", this::releaseCurrentTenantExpiredLocks);
    }

    private void releaseCurrentTenantExpiredLocks() {
        LocalDateTime now = LocalDateTime.now();
        // 查询所有已过期但仍被锁定的文件节点
        var expiredNodes = fileNodeMapper.selectList(new LambdaQueryWrapper<FileNode>()
                .isNotNull(FileNode::getLockedBy)
                .isNotNull(FileNode::getLockExpireAt)
                .lt(FileNode::getLockExpireAt, now));
        if (expiredNodes.isEmpty()) return;
        int released = 0;
        // 当前锁身份及过期时间必须仍与扫描快照一致，延期/重新锁定不会被旧扫描释放。
        for (FileNode node : expiredNodes) {
            LambdaUpdateWrapper<FileNode> update = new LambdaUpdateWrapper<FileNode>()
                    .eq(FileNode::getId, node.getId())
                    .eq(FileNode::getLockedBy, node.getLockedBy())
                    .eq(FileNode::getLockExpireAt, node.getLockExpireAt())
                    .lt(FileNode::getLockExpireAt, now);
            if (node.getLockedAt() == null) update.isNull(FileNode::getLockedAt);
            else update.eq(FileNode::getLockedAt, node.getLockedAt());
            released += fileNodeMapper.update(null, update
                    .set(FileNode::getLockedBy, null)
                    .set(FileNode::getLockedAt, null)
                    .set(FileNode::getLockExpireAt, null));
        }
        log.info("文件锁过期清理：释放 {} 个过期锁", released);
    }
}
