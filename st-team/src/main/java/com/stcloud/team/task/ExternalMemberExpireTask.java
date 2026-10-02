package com.stcloud.team.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.team.entity.TeamMember;
import com.stcloud.team.mapper.TeamMemberMapper;
import com.stcloud.team.mapper.TeamSpaceMapper;
import com.stcloud.core.task.TenantTaskRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 外部协作者过期自动移除定时任务：每小时检查并移除过期的外部协作者。
 * expire_at 早于当前时间的外部成员（member_type=1）自动删除 team_member 记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalMemberExpireTask {

    private final TeamMemberMapper teamMemberMapper;
    private final TeamSpaceMapper teamSpaceMapper;
    private final TenantTaskRunner tenantTaskRunner;
    private final PlatformTransactionManager transactionManager;

    @Scheduled(cron = "0 30 * * * ?")
    public void removeExpiredExternalMembers() {
        tenantTaskRunner.runForEachTenant("外部协作者过期清理", this::removeCurrentTenantExpiredMembers);
    }

    private void removeCurrentTenantExpiredMembers() {
        LocalDateTime now = LocalDateTime.now();
        // 查询已过期的外部协作者
        var expired = teamMemberMapper.selectList(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getMemberType, 1)
                .isNotNull(TeamMember::getExpireAt)
                .lt(TeamMember::getExpireAt, now));
        if (expired.isEmpty()) return;
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        int removed = 0;
        for (TeamMember member : expired) {
            Integer count = transaction.execute(status -> {
                // 与人工撤权、回收写入统一空间→成员锁序，避免旧权限快照继续写入。
                if (!member.getSpaceId().equals(teamSpaceMapper.lockRoleWrites(member.getSpaceId()))) return 0;
                // 扫描后若成员延期或转为内部成员，旧快照不能删除当前有效成员。
                return teamMemberMapper.delete(new LambdaQueryWrapper<TeamMember>()
                        .eq(TeamMember::getId, member.getId())
                        .eq(TeamMember::getSpaceId, member.getSpaceId())
                        .eq(TeamMember::getMemberType, 1)
                        .eq(TeamMember::getExpireAt, member.getExpireAt())
                        .lt(TeamMember::getExpireAt, now));
            });
            removed += count != null ? count : 0;
        }
        log.info("外部协作者过期清理：移除 {} 个过期外部成员", removed);
    }
}
