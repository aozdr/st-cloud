package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.auth.entity.SysUser;
import com.stcloud.common.response.Result;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.team.AbstractTeamIntegrationTest;
import com.stcloud.team.dto.CreateSpaceRequest;
import com.stcloud.team.dto.InviteMemberRequest;
import com.stcloud.team.dto.TeamMemberVO;
import com.stcloud.team.dto.TeamRoleRequest;
import com.stcloud.team.dto.TeamRoleVO;
import com.stcloud.team.dto.TeamSpaceVO;
import com.stcloud.team.dto.TeamStatsVO;
import com.stcloud.team.entity.Notification;
import com.stcloud.team.entity.TeamActivity;
import com.stcloud.team.entity.TeamMember;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * st-team 空间/成员/角色/统计 H2 集成测试（H7 Code Review 补测试）。
 * <p>
 * 覆盖：空间创建/列表、成员邀请/列表/角色变更、自定义角色（含 C2 新增 roles 端点对应 Service）、
 * 空间统计（含 C2 新增 stats 端点对应 Service）。全部走真实 H2 + MyBatis-Plus（租户隔离/自动填充）。
 */
class TeamServiceIntegrationTest extends AbstractTeamIntegrationTest {

    private CreateSpaceRequest spaceRequest(String name) {
        CreateSpaceRequest request = new CreateSpaceRequest();
        request.setSpaceName(name);
        request.setDescription("集成测试空间");
        request.setStorageQuota(1024L * 1024 * 1024);
        return request;
    }

    private Long createSpaceAs(Long userId) {
        setUpUser(userId, 1L);
        Result<TeamSpaceVO> result = teamService.createSpace(spaceRequest("测试空间-" + userId));
        assertEquals(200, result.getCode());
        return result.getData().getId();
    }

    @Test
    void createSpace_createsSpaceAndOwnerAdminMember() {
        insertUser(100L, 1L, "owner");
        Long spaceId = createSpaceAs(100L);

        // 空间落库（真实 Mapper + 自动填充）
        var space = teamSpaceMapper.selectById(spaceId);
        assertNotNull(space);
        assertEquals("测试空间-100", space.getSpaceName());
        assertEquals(100L, space.getOwnerId());
        assertEquals(1, space.getStatus());
        assertEquals(1024L * 1024 * 1024, space.getStorageQuota());

        // 创建者自动成为管理员（role=0）
        List<TeamMember> members = teamMemberMapper.selectList(
                new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getSpaceId, spaceId));
        assertEquals(1, members.size());
        assertEquals(100L, members.get(0).getUserId());
        assertEquals(0, members.get(0).getRole());
    }

    @Test
    void listSpaces_returnsCreatedSpaceWithMemberCount() {
        insertUser(100L, 1L, "owner");
        Long spaceId = createSpaceAs(100L);

        Result<IPage<TeamSpaceVO>> result = teamService.listSpaces(1, 20, null, null);

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().getTotal());
        TeamSpaceVO vo = result.getData().getRecords().get(0);
        assertEquals(spaceId, vo.getId());
        assertEquals("测试空间-100", vo.getSpaceName());
        assertEquals(1, vo.getMemberCount());
        assertEquals(100L, vo.getOwnerId());
    }

    @Test
    void inviteMember_addsMemberAndSendsNotification() {
        insertUser(100L, 1L, "owner");
        insertUser(200L, 1L, "member");
        Long spaceId = createSpaceAs(100L);

        InviteMemberRequest request = new InviteMemberRequest();
        request.setUserId(200L);
        Result<TeamMemberVO> result = teamService.inviteMember(spaceId, request);

        assertEquals(200, result.getCode());
        assertEquals(200L, result.getData().getUserId());
        assertEquals(2, result.getData().getRole()); // 默认查看者

        // 成员表新增一条记录
        Long memberCount = teamMemberMapper.selectCount(
                new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getSpaceId, spaceId));
        assertEquals(2, memberCount);

        // 邀请通知落库
        Long notifCount = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, 200L)
                .eq(Notification::getType, "TEAM_INVITE"));
        assertEquals(1, notifCount);
    }

    @Test
    void listMembers_returnsMembersWithUserInfo() {
        insertUser(100L, 1L, "owner");
        insertUser(200L, 1L, "member");
        Long spaceId = createSpaceAs(100L);
        InviteMemberRequest request = new InviteMemberRequest();
        request.setUserId(200L);
        teamService.inviteMember(spaceId, request);

        Result<IPage<TeamMemberVO>> result = teamService.listMembers(spaceId, 1, 50, null);

        assertEquals(200, result.getCode());
        assertEquals(2, result.getData().getTotal());
        List<TeamMemberVO> records = result.getData().getRecords();
        // 默认按角色升序：管理员(0)在前，查看者(2)在后
        assertEquals(0, records.get(0).getRole());
        assertEquals("昵称-owner", records.get(0).getNickname());
        assertEquals(2, records.get(1).getRole());
        assertEquals("昵称-member", records.get(1).getNickname());
    }

    @Test
    void updateMemberRole_changesMemberRole() {
        insertUser(100L, 1L, "owner");
        insertUser(200L, 1L, "member");
        Long spaceId = createSpaceAs(100L);
        InviteMemberRequest request = new InviteMemberRequest();
        request.setUserId(200L);
        Long memberId = teamService.inviteMember(spaceId, request).getData().getId();

        Result<Void> result = teamService.updateMemberRole(spaceId, memberId, 1);

        assertEquals(200, result.getCode());
        TeamMember member = teamMemberMapper.selectById(memberId);
        assertEquals(1, member.getRole());
    }

    @Test
    void listRoles_returnsPresetRoles() {
        insertUser(100L, 1L, "owner");
        Long spaceId = createSpaceAs(100L);

        Result<List<TeamRoleVO>> result = teamService.listRoles(spaceId);

        assertEquals(200, result.getCode());
        assertEquals(3, result.getData().size());
        List<String> names = result.getData().stream().map(TeamRoleVO::getName).toList();
        assertTrue(names.contains("管理员"));
        assertTrue(names.contains("编辑者"));
        assertTrue(names.contains("查看者"));
        assertTrue(result.getData().stream().allMatch(TeamRoleVO::getIsPreset));
    }

    @Test
    void createAndDeleteRole_lifecycle() {
        insertUser(100L, 1L, "owner");
        Long spaceId = createSpaceAs(100L);

        TeamRoleRequest roleRequest = new TeamRoleRequest();
        roleRequest.setName("审核员");
        roleRequest.setPermissions("{\"view\":true,\"upload\":false}");
        Result<TeamRoleVO> created = teamService.createRole(spaceId, roleRequest);

        assertEquals(200, created.getCode());
        assertFalse(created.getData().getIsPreset());
        assertEquals("审核员", created.getData().getName());
        Long roleId = created.getData().getId();

        // 自定义角色出现在角色列表
        List<TeamRoleVO> withCustom = teamService.listRoles(spaceId).getData();
        assertEquals(4, withCustom.size());
        assertTrue(withCustom.stream().anyMatch(r -> roleId.equals(r.getId()) && Boolean.FALSE.equals(r.getIsPreset())));

        // 删除后回到 3 个预设角色
        assertEquals(200, teamService.deleteRole(spaceId, roleId).getCode());
        assertEquals(3, teamService.listRoles(spaceId).getData().size());
    }

    @Test
    void getStats_returnsStorageFilesActivitiesAndMembers() {
        insertUser(100L, 1L, "owner");
        Long spaceId = createSpaceAs(100L);

        // 空间内文件：2 张图片、1 个视频、1 个文档
        insertFileNode(1L, 100L, spaceId, "a.png", 1, 0);
        insertFileNode(1L, 100L, spaceId, "b.png", 1, 0);
        insertFileNode(1L, 100L, spaceId, "movie.mp4", 1, 0);
        insertFileNode(1L, 100L, spaceId, "doc.txt", 1, 0);

        // 活动日志：2 次上传 + 1 次删除
        insertActivity(1L, spaceId, 100L, "FILE_UPLOAD", "FILE", null, null);
        insertActivity(1L, spaceId, 100L, "FILE_UPLOAD", "FILE", null, null);
        insertActivity(1L, spaceId, 100L, "FILE_DELETE", "FILE", null, null);

        Result<TeamStatsVO> result = teamService.getStats(spaceId, 30);

        assertEquals(200, result.getCode());
        TeamStatsVO stats = result.getData();
        assertEquals(1024L * 1024 * 1024, stats.getStorageQuota());
        assertEquals(4, stats.getFileCount());

        // 类型分布：图片 2 / 视频 1 / 文档 1
        Map<String, Long> typeCount = stats.getFileTypeDistribution().stream()
                .collect(java.util.stream.Collectors.toMap(
                        m -> String.valueOf(m.get("type")),
                        m -> Long.valueOf(String.valueOf(m.get("count")))));
        assertEquals(2L, typeCount.get("图片"));
        assertEquals(1L, typeCount.get("视频"));
        assertEquals(1L, typeCount.get("文档"));

        // 成员活跃度：空间拥有者在列
        assertEquals(1, stats.getMemberActivity().size());
        assertEquals(100L, Long.valueOf(String.valueOf(stats.getMemberActivity().get(0).get("userId"))));
        assertEquals("昵称-owner", stats.getMemberActivity().get(0).get("nickname"));

        // 操作统计：FILE_UPLOAD=2 / FILE_DELETE=1
        Map<String, Long> actionCount = stats.getOperationStats().stream()
                .collect(java.util.stream.Collectors.toMap(
                        m -> String.valueOf(m.get("action")),
                        m -> Long.valueOf(String.valueOf(m.get("count")))));
        assertEquals(2L, actionCount.get("FILE_UPLOAD"));
        assertEquals(1L, actionCount.get("FILE_DELETE"));
    }

    @Test
    void lockFile_voReturnsLockFieldsAndUnlockClearsThem() {
        insertUser(100L, 1L, "owner");
        Long spaceId = createSpaceAs(100L);
        FileNode node = insertFileNode(1L, 100L, spaceId, "locked.txt", 1, 0);

        // 锁定 24 小时：VO 返回锁定字段（团队文件详情走 FileServiceImpl.toVO）
        assertEquals(200, teamService.lockFile(spaceId, node.getId(), 24).getCode());
        FileNodeVO locked = fileService.getTeamNodeById(spaceId, node.getId());
        assertEquals(100L, locked.getLockedBy());
        assertNotNull(locked.getLockedAt());
        assertNotNull(locked.getLockExpireAt());

        // 永久锁定（hours=0）：lockExpireAt 为 null（永久锁）
        assertEquals(200, teamService.unlockFile(spaceId, node.getId()).getCode());
        assertEquals(200, teamService.lockFile(spaceId, node.getId(), 0).getCode());
        FileNodeVO permanent = fileService.getTeamNodeById(spaceId, node.getId());
        assertEquals(100L, permanent.getLockedBy());
        assertNotNull(permanent.getLockedAt());
        assertNull(permanent.getLockExpireAt());

        // 解锁后 VO 锁定字段为空
        assertEquals(200, teamService.unlockFile(spaceId, node.getId()).getCode());
        FileNodeVO unlocked = fileService.getTeamNodeById(spaceId, node.getId());
        assertNull(unlocked.getLockedBy());
        assertNull(unlocked.getLockedAt());
        assertNull(unlocked.getLockExpireAt());
    }

    private void insertActivity(Long tenantId, Long spaceId, Long userId, String action,
                                String targetType, Long targetId, String targetName) {
        TeamActivity activity = new TeamActivity();
        activity.setTenantId(tenantId);
        activity.setSpaceId(spaceId);
        activity.setUserId(userId);
        activity.setUsername("test-user-" + userId);
        activity.setNickname("昵称-" + userId);
        activity.setAction(action);
        activity.setTargetType(targetType);
        activity.setTargetId(targetId);
        activity.setTargetName(targetName);
        activity.setCreatedAt(LocalDateTime.now());
        teamActivityMapper.insert(activity);
    }
}
