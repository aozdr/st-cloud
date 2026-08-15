package com.stcloud.share;

import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.share.mapper.FileShareMapper;
import com.stcloud.share.service.ShareService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * st-share 集成测试基类。
 * <p>
 * 使用 H2 内存库 + 真实 MyBatis-Plus（含租户拦截器、自动填充），验证 SQL/Mapper/表结构/租户隔离。
 * {@code @Transactional} 保证每个测试方法执行后自动回滚，无需手动清理数据。
 */
@SpringBootTest(classes = ShareTestApplication.class)
@ActiveProfiles("test")
@Transactional
public abstract class AbstractShareIntegrationTest {

    @Autowired
    protected ShareService shareService;

    @Autowired
    protected FileShareMapper fileShareMapper;

    @Autowired
    protected FileNodeMapper fileNodeMapper;

    @AfterEach
    void clearContext() {
        UserContext.clear();
        TenantContext.clear();
    }

    /**
     * 设置当前用户/租户上下文（分享创建/修改依赖 UserContext/TenantContext ThreadLocal）。
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
     * 构建并插入一个测试文件节点（走真实 Mapper，验证 INSERT SQL + 自动填充）。
     *
     * @param status 0=正常 1=回收站
     */
    protected FileNode insertFileNode(Long tenantId, Long ownerId, String name, int status) {
        return insertFileNode(tenantId, ownerId, name, status, null);
    }

    /**
     * 构建并插入一个测试文件节点（可指定团队空间 spaceId；spaceId 为空表示个人文件）。
     */
    protected FileNode insertFileNode(Long tenantId, Long ownerId, String name, int status, Long spaceId) {
        FileNode node = new FileNode();
        node.setTenantId(tenantId);
        node.setParentId(0L);
        node.setNodeType(1);
        node.setName(name);
        node.setPath("/" + name);
        node.setFileSize(1024L);
        node.setContentType("text/plain");
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

    /**
     * 构建并插入一个测试文件夹节点（nodeType=0）。
     */
    protected FileNode insertFolder(Long tenantId, Long ownerId, String name) {
        FileNode folder = new FileNode();
        folder.setTenantId(tenantId);
        folder.setParentId(0L);
        folder.setNodeType(0);
        folder.setName(name);
        folder.setPath("/" + name);
        folder.setFileSize(0L);
        folder.setStatus(0);
        folder.setUploadStatus(2);
        folder.setUploaderId(ownerId);
        folder.setOwnerId(ownerId);
        folder.setVersion(0);
        fileNodeMapper.insert(folder);
        return folder;
    }
}
