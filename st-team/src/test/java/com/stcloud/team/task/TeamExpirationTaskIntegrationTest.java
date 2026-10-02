package com.stcloud.team.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.TenantScanMapper;
import com.stcloud.core.task.TenantTaskRunner;
import com.stcloud.team.AbstractTeamIntegrationTest;
import com.stcloud.team.entity.TeamMember;
import com.stcloud.team.entity.TeamSpace;
import com.stcloud.team.mapper.TeamMemberMapper;
import com.stcloud.team.mapper.TeamSpaceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** 真实 H2 Mapper 执行旧扫描后 CAS，测试事务回滚；不连接开发库或启动调度。 */
class TeamExpirationTaskIntegrationTest extends AbstractTeamIntegrationTest {
    @Autowired PlatformTransactionManager transactionManager;

    private TenantTaskRunner runner(Long... tenants) {
        TenantScanMapper scan = mock(TenantScanMapper.class);
        when(scan.selectEnabledTenantIds()).thenReturn(List.of(tenants));
        return new TenantTaskRunner(scan);
    }

    private TeamMember member(long tenant, long space, long id) {
        TenantContext.setTenantId(tenant); TenantContext.setTenantMode("SAAS");
        TeamSpace team = new TeamSpace(); team.setId(space); team.setTenantId(tenant);
        team.setSpaceName("expiry-" + id); team.setOwnerId(3L); team.setStatus(1);
        teamSpaceMapper.insert(team);
        TeamMember member = new TeamMember(); member.setId(id); member.setTenantId(tenant);
        member.setSpaceId(space); member.setUserId(3L); member.setRole(2L); member.setMemberType(1);
        member.setExpireAt(LocalDateTime.now().minusHours(2)); member.setJoinedAt(LocalDateTime.now());
        teamMemberMapper.insert(member); return member;
    }

    private FileNode locked(long tenant, String name) {
        setUpUser(3L, tenant);
        FileNode node = insertFileNode(tenant, 3L, null, name, 1, 0);
        node.setLockedBy(3L); node.setLockedAt(LocalDateTime.now().minusHours(3));
        node.setLockExpireAt(LocalDateTime.now().minusHours(2)); fileNodeMapper.updateById(node);
        return fileNodeMapper.selectById(node.getId());
    }

    @Test void externalExpirationVisitsTwoTenantsAndLocksSpaceBeforeMemberWrite() {
        TeamMember first = member(71L, 77101L, 77111L);
        TeamMember second = member(72L, 77201L, 77211L);
        List<Long> locked = new ArrayList<>();
        TeamSpaceMapper spaces = mock(TeamSpaceMapper.class, delegatesTo(teamSpaceMapper));
        when(spaces.lockRoleWrites(anyLong())).thenAnswer(call -> {
            Long space = call.getArgument(0); Long actual = teamSpaceMapper.lockRoleWrites(space);
            locked.add(space); return actual;
        });
        TeamMemberMapper members = mock(TeamMemberMapper.class, delegatesTo(teamMemberMapper));
        doAnswer(call -> {
            assertTrue(locked.contains(TenantContext.getTenantIdOrNull().equals(71L) ? 77101L : 77201L));
            return teamMemberMapper.delete(call.getArgument(0));
        }).when(members).delete(any(LambdaQueryWrapper.class));
        TenantContext.clear();
        new ExternalMemberExpireTask(members, spaces, runner(71L, 72L), transactionManager).removeExpiredExternalMembers();
        assertNull(TenantContext.getTenantIdOrNull()); assertNull(TenantContext.getTenantModeOrNull());
        TenantContext.setTenantId(71L); assertNull(teamMemberMapper.selectById(first.getId()));
        TenantContext.setTenantId(72L); assertNull(teamMemberMapper.selectById(second.getId()));
        assertEquals(List.of(77101L, 77201L), locked);
    }

    @Test void extendedOrConvertedExternalMemberSurvivesOldScan() {
        TeamMember first = member(71L, 77301L, 77311L);
        TeamMember second = member(72L, 77401L, 77411L);
        TeamMemberMapper members = mock(TeamMemberMapper.class, delegatesTo(teamMemberMapper));
        doAnswer(call -> {
            List<TeamMember> scanned = teamMemberMapper.selectList(call.getArgument(0));
            for (TeamMember value : scanned) {
                LambdaUpdateWrapper<TeamMember> update = new LambdaUpdateWrapper<TeamMember>().eq(TeamMember::getId, value.getId());
                if (value.getTenantId().equals(71L)) update.set(TeamMember::getExpireAt, LocalDateTime.now().plusHours(1));
                else update.set(TeamMember::getMemberType, 0);
                teamMemberMapper.update(null, update);
            }
            return scanned;
        }).when(members).selectList(any(LambdaQueryWrapper.class));
        TenantContext.clear();
        new ExternalMemberExpireTask(members, teamSpaceMapper, runner(71L, 72L), transactionManager).removeExpiredExternalMembers();
        assertNull(TenantContext.getTenantIdOrNull());
        TenantContext.setTenantId(71L); assertNotNull(teamMemberMapper.selectById(first.getId()));
        TenantContext.setTenantId(72L); assertEquals(0, teamMemberMapper.selectById(second.getId()).getMemberType());
    }

    @Test void fileExpirationVisitsTwoTenantsAndRestoresCallerContext() {
        FileNode first = locked(71L, "expired-71.txt"), second = locked(72L, "expired-72.txt");
        TenantContext.setTenantId(91L); TenantContext.setTenantMode("SAAS");
        new FileLockExpireTask(fileNodeMapper, runner(71L, 72L)).releaseExpiredLocks();
        assertEquals(91L, TenantContext.getTenantIdOrNull()); assertEquals("SAAS", TenantContext.getTenantModeOrNull());
        TenantContext.setTenantId(71L); assertNull(fileNodeMapper.selectById(first.getId()).getLockedBy());
        TenantContext.setTenantId(72L); assertNull(fileNodeMapper.selectById(second.getId()).getLockedBy());
    }

    @Test void extendedOrRelockedFileSurvivesOldScan() {
        FileNode first = locked(71L, "extended-71.txt"), second = locked(72L, "relocked-72.txt");
        FileNodeMapper nodes = mock(FileNodeMapper.class, delegatesTo(fileNodeMapper));
        doAnswer(call -> {
            List<FileNode> scanned = fileNodeMapper.selectList(call.getArgument(0));
            for (FileNode value : scanned) {
                LambdaUpdateWrapper<FileNode> update = new LambdaUpdateWrapper<FileNode>().eq(FileNode::getId, value.getId());
                if (value.getTenantId().equals(71L)) update.set(FileNode::getLockExpireAt, LocalDateTime.now().plusHours(1));
                else update.set(FileNode::getLockedBy, 4L).set(FileNode::getLockedAt, LocalDateTime.now());
                fileNodeMapper.update(null, update);
            }
            return scanned;
        }).when(nodes).selectList(any(LambdaQueryWrapper.class));
        TenantContext.clear(); new FileLockExpireTask(nodes, runner(71L, 72L)).releaseExpiredLocks();
        assertNull(TenantContext.getTenantIdOrNull());
        TenantContext.setTenantId(71L); assertEquals(3L, fileNodeMapper.selectById(first.getId()).getLockedBy());
        TenantContext.setTenantId(72L); assertEquals(4L, fileNodeMapper.selectById(second.getId()).getLockedBy());
    }
}
