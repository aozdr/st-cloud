package com.stcloud.team;

import com.stcloud.auth.entity.SysUser;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.FileService;
import com.stcloud.team.mapper.NotificationMapper;
import com.stcloud.team.mapper.TeamActivityMapper;
import com.stcloud.team.mapper.TeamCommentMapper;
import com.stcloud.team.mapper.TeamExternalConfigMapper;
import com.stcloud.team.mapper.TeamFolderPermissionMapper;
import com.stcloud.team.mapper.TeamInviteMapper;
import com.stcloud.team.mapper.TeamMemberMapper;
import com.stcloud.team.mapper.TeamRoleMapper;
import com.stcloud.team.mapper.TeamSpaceMapper;
import com.stcloud.team.service.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * st-team 集成测试基类。
 * <p>
 * 使用 H2 内存库 + 真实 MyBatis-Plus（含租户拦截器、自动填充），验证 SQL/Mapper/表结构/租户隔离。
 * {@code @Transactional} 保证每个测试方法执行后自动回滚，无需手动清理数据。
 * 子类通过 {@link #setUpUser} 设置用户/租户上下文。
 */
@SpringBootTest(classes = TeamTestApplication.class)
@ActiveProfiles("test")
@Transactional
public abstract class AbstractTeamIntegrationTest {

    @Autowired
    protected TeamService teamService;

    @Autowired
    protected TeamSpaceMapper teamSpaceMapper;

    @Autowired
    protected TeamMemberMapper teamMemberMapper;

    @Autowired
    protected TeamInviteMapper teamInviteMapper;

    @Autowired
    protected TeamActivityMapper teamActivityMapper;

    @Autowired
    protected TeamRoleMapper teamRoleMapper;

    @Autowired
    protected TeamExternalConfigMapper teamExternalConfigMapper;

    @Autowired
    protected TeamCommentMapper teamCommentMapper;

    @Autowired
    protected TeamFolderPermissionMapper teamFolderPermissionMapper;

    @Autowired
    protected NotificationMapper notificationMapper;

    @Autowired
    protected SysUserMapper sysUserMapper;

    @Autowired
    protected FileNodeMapper fileNodeMapper;

    @Autowired
    protected FileService fileService;

    @AfterEach
    void clearContext() {
        UserContext.clear();
        TenantContext.clear();
    }

    /**
     * 设置当前用户/租户上下文（团队服务依赖 UserContext/TenantContext ThreadLocal）。
     * 必须在每个测试方法前调用，否则 Service 会抛 UNAUTHORIZED 或租户拦截异常。
     */
    protected void setUpUser(Long userId, Long tenantId) {
        TenantContext.setTenantId(tenantId);
        TenantContext.setTenantMode("SAAS");
        UserContext.setCurrentUser(UserContext.CurrentUser.builder()
                .userId(userId)
                .tenantId(tenantId)
                .username("test-user-" + userId)
                .build());
    }

    /**
     * 构建并插入一个测试用户（走真实 Mapper，验证 INSERT SQL + 自动填充）。
     */
    protected SysUser insertUser(Long id, Long tenantId, String username) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setUsername(username);
        user.setPassword("test-password");
        user.setNickname("昵称-" + username);
        user.setStatus(1);
        sysUserMapper.insert(user);
        return user;
    }

    /**
     * 构建并插入一个测试文件节点（走真实 Mapper）。
     *
     * @param nodeType 0=文件夹 1=文件
     * @param status   0=正常 1=回收站
     */
    protected FileNode insertFileNode(Long tenantId, Long ownerId, Long spaceId, String name, int nodeType, int status) {
        FileNode node = new FileNode();
        node.setTenantId(tenantId);
        node.setParentId(0L);
        node.setNodeType(nodeType);
        node.setName(name);
        node.setPath("/" + name);
        node.setFileSize(1024L);
        node.setContentType("application/octet-stream");
        int dotIdx = name.lastIndexOf(".");
        node.setSuffix(dotIdx > 0 ? name.substring(dotIdx + 1) : null);
        node.setStatus(status);
        node.setUploadStatus(2);
        node.setUploaderId(ownerId);
        node.setOwnerId(ownerId);
        node.setSpaceId(spaceId);
        node.setVersion(0);
        fileNodeMapper.insert(node);
        return node;
    }
}
