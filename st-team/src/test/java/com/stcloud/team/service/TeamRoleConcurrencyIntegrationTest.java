package com.stcloud.team.service;

import com.stcloud.team.AbstractTeamIntegrationTest;
import com.stcloud.team.dto.*;
import com.stcloud.team.entity.TeamRole;
import com.stcloud.team.mapper.TeamSpaceMapper;
import com.stcloud.common.exception.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;

/** 跨线程真实提交；不继承基类外层回滚事务，避免把未提交夹具当并发证据。 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeamRoleConcurrencyIntegrationTest extends AbstractTeamIntegrationTest {
    @SpyBean TeamSpaceMapper spaceLocks;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @SpyBean FolderPermissionService folders;
    private static final java.util.concurrent.atomic.AtomicLong IDS=new java.util.concurrent.atomic.AtomicLong(9100000000000000L);

    @ParameterizedTest @ValueSource(strings={"role-grant-commit","role-grant-rollback","role-revoke-commit","role-revoke-rollback",
            "acl-grant-commit","acl-grant-rollback","acl-revoke-commit","acl-revoke-rollback"})
    void tc0220UncommittedPermissionsStayInvisibleAndRollbackIsComplete(String mode) throws Exception {
        long owner=IDS.incrementAndGet(),candidate=IDS.incrementAndGet(),roleId=IDS.incrementAndGet();
        setUpUser(owner,1L);insertUser(owner,1L,"tx-owner-"+owner);insertUser(candidate,1L,"tx-member-"+candidate);
        var create=new CreateSpaceRequest();create.setSpaceName("tx-space-"+owner);create.setStorageQuota(100000L);
        Long space=teamService.createSpace(create).getData().getId();
        boolean initial=mode.contains("revoke"),acl=mode.startsWith("acl"),rollback=mode.endsWith("rollback");
        var role=new TeamRole();role.setId(roleId);role.setSpaceId(space);role.setName("事务角色");
        role.setPermissions("{\"view\":true,\"upload\":"+(!acl&&initial)+"}");role.setStatus(1);teamRoleMapper.insert(role);
        var invite=new InviteMemberRequest();invite.setUserId(candidate);invite.setRole(roleId);teamService.inviteMember(space,invite);
        if(acl&&initial) teamService.setFolderPermissions(space,0L,uploadRule(true));
        var observer=Executors.newSingleThreadExecutor();
        Callable<Boolean> canUpload=()-> {
            setUpUser(candidate,1L);
            try {teamService.requirePermissions(space,0L,"upload");return true;}
            catch(BusinessException denied){return false;}
            finally {com.stcloud.common.context.UserContext.clear();com.stcloud.common.context.TenantContext.clear();}
        };
        try {
            assertEquals(initial,observer.submit(canUpload).get(5,TimeUnit.SECONDS));
            // 故意保留展示缓存：授权正确性不能依赖缓存清理成功。
            org.mockito.Mockito.doNothing().when(folders).invalidateSpace(space);
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
                if(acl) teamService.setFolderPermissions(space,0L,uploadRule(!initial));
                else {var update=new TeamRoleRequest();update.setName("事务角色");update.setPermissions("{\"view\":true,\"upload\":"+(!initial)+"}");teamService.updateRole(space,roleId,update);}
                try {assertEquals(initial,observer.submit(canUpload).get(5,TimeUnit.SECONDS),"未提交权限不得泄漏到其他连接");}
                catch(Exception error){throw new AssertionError(error);}
                if(rollback) status.setRollbackOnly();
            });
            assertEquals(rollback?initial:!initial,observer.submit(canUpload).get(5,TimeUnit.SECONDS));
        } finally {observer.shutdownNow();assertTrue(observer.awaitTermination(5,TimeUnit.SECONDS));}
    }
    private FolderPermissionRequest uploadRule(boolean upload) {
        var rule=new FolderPermissionRequest.PermissionRule();rule.setSubjectType("all");rule.setSubjectId("0");
        rule.setPermissions("{\"view\":true,\"upload\":"+upload+"}");
        var request=new FolderPermissionRequest();request.setRules(java.util.List.of(rule));return request;
    }

    @ParameterizedTest @ValueSource(strings={"direct-ref","direct-delete","link-ref","link-delete","member-ref","member-delete","accept-ref","accept-delete"})
    void tc0213NoDanglingReferenceAcrossSpaceLockOrders(String scenario) throws Exception {
        long owner=IDS.incrementAndGet(),candidate=IDS.incrementAndGet(),roleId=IDS.incrementAndGet();
        setUpUser(owner,1L); insertUser(owner,1L,"race-owner-"+owner); insertUser(candidate,1L,"race-member-"+candidate);
        var create=new CreateSpaceRequest(); create.setSpaceName("race-space-"+owner); create.setStorageQuota(100000L);
        Long space=teamService.createSpace(create).getData().getId();
        var role=new TeamRole(); role.setId(roleId); role.setSpaceId(space); role.setName("并发角色"); role.setPermissions("{\"view\":true}"); role.setStatus(1); teamRoleMapper.insert(role);
        Long existingMember;
        if(scenario.startsWith("member")) {
            var invite=new InviteMemberRequest(); invite.setUserId(candidate);
            existingMember=teamService.inviteMember(space,invite).getData().getId();
        } else existingMember=null;
        String code;
        if(scenario.startsWith("accept")) {
            var invite=new CreateInviteRequest(); invite.setRole(roleId);
            code=teamService.createInvite(space,invite).getData().getInviteCode();
        } else code=null;
        var held=new CountDownLatch(1); var attempted=new CountDownLatch(1); var resume=new CountDownLatch(1);
        TeamSpaceMapper actual=sqlSession.getMapper(TeamSpaceMapper.class);
        doAnswer(inv->{
            if(Thread.currentThread().getName().equals("team-second")) attempted.countDown();
            Long result=actual.lockRoleWrites(space);
            if(Thread.currentThread().getName().equals("team-first")) {
                held.countDown(); assertTrue(resume.await(10,TimeUnit.SECONDS));
            }
            return result;
        }).when(spaceLocks).lockRoleWrites(space);
        Callable<Object> reference=()-> {
            setUpUser(code==null?owner:candidate,1L);
            try {
                if(scenario.startsWith("direct")) {var request=new InviteMemberRequest();request.setUserId(candidate);request.setRole(roleId);teamService.inviteMember(space,request);}
                else if(scenario.startsWith("link")) {var request=new CreateInviteRequest();request.setRole(roleId);teamService.createInvite(space,request);}
                else if(scenario.startsWith("member")) teamService.updateMemberRole(space,existingMember,roleId);
                else teamService.joinByCode(code);
                return "ok";
            } catch(BusinessException rejected) {return rejected;} finally {com.stcloud.common.context.UserContext.clear(); com.stcloud.common.context.TenantContext.clear();}
        };
        Callable<Object> deletion=()-> {
            setUpUser(owner,1L);try {teamService.deleteRole(space,roleId);return "ok";}
            catch(BusinessException rejected) {return rejected;} finally {com.stcloud.common.context.UserContext.clear(); com.stcloud.common.context.TenantContext.clear();}
        };
        var firstPool=Executors.newSingleThreadExecutor(r->new Thread(r,"team-first"));
        var secondPool=Executors.newSingleThreadExecutor(r->new Thread(r,"team-second"));
        boolean referenceFirst=scenario.endsWith("ref");
        try {
            var first=firstPool.submit(referenceFirst?reference:deletion);
            assertTrue(held.await(10,TimeUnit.SECONDS));
            var second=secondPool.submit(referenceFirst?deletion:reference);
            assertTrue(attempted.await(10,TimeUnit.SECONDS)); assertFalse(second.isDone());
            resume.countDown(); Object firstResult=first.get(15,TimeUnit.SECONDS),secondResult=second.get(15,TimeUnit.SECONDS);
            Object referenceResult=referenceFirst?firstResult:secondResult,deletionResult=referenceFirst?secondResult:firstResult;
            // 接受邀请前已有有效引用，因此无论锁顺序，删除都必须拒绝。
            boolean referenceWins=referenceFirst||code!=null;
            assertEquals("ok",referenceWins?referenceResult:deletionResult);
            assertInstanceOf(BusinessException.class,referenceWins?deletionResult:referenceResult);
            setUpUser(owner,1L);
            assertEquals(referenceWins,teamRoleMapper.selectById(roleId)!=null);
            int refs=jdbc.queryForObject("SELECT COUNT(*) FROM team_member WHERE space_id=? AND role=? AND deleted=0",Integer.class,space,roleId)
                    +jdbc.queryForObject("SELECT COUNT(*) FROM team_invite WHERE space_id=? AND role=? AND status=1 AND deleted=0",Integer.class,space,roleId);
            assertEquals(referenceWins?(code!=null?2:1):0,refs);
        } finally {
            resume.countDown();firstPool.shutdownNow();secondPool.shutdownNow();
            assertTrue(firstPool.awaitTermination(5,TimeUnit.SECONDS));assertTrue(secondPool.awaitTermination(5,TimeUnit.SECONDS));
        }
    }
}
