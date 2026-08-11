package com.stcloud.team.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.team.entity.TeamMember;
import com.stcloud.team.mapper.TeamMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    @Scheduled(cron = "0 30 * * * ?")
    public void removeExpiredExternalMembers() {
        LocalDateTime now = LocalDateTime.now();
        // 查询已过期的外部协作者
        var expired = teamMemberMapper.selectList(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getMemberType, 1)
                .isNotNull(TeamMember::getExpireAt)
                .lt(TeamMember::getExpireAt, now));
        if (expired.isEmpty()) return;
        for (TeamMember member : expired) {
            teamMemberMapper.deleteById(member.getId());
        }
        log.info("外部协作者过期清理：移除 {} 个过期外部成员", expired.size());
    }
}