package com.stcloud.core;

import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileFavoriteMapper;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.impl.FavoriteServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * st-core 集成测试基类。
 * <p>
 * 使用 H2 内存库 + 真实 MyBatis-Plus（含租户拦截器、自动填充），验证 SQL/Mapper/表结构/租户隔离。
 * {@code @Transactional} 保证每个测试方法执行后自动回滚，无需手动清理数据。
 * 子类通过 {@link #setUpUser} 设置用户/租户上下文（收藏功能依赖 ThreadLocal）。
 */
@SpringBootTest(classes = CoreTestApplication.class)
@ActiveProfiles("test")
@Transactional
public abstract class AbstractIntegrationTest {

    @Autowired
    protected FavoriteServiceImpl favoriteService;

    @Autowired
    protected FileNodeMapper fileNodeMapper;

    @Autowired
    protected FileFavoriteMapper fileFavoriteMapper;

    @AfterEach
    void clearContext() {
        UserContext.clear();
        TenantContext.clear();
    }

    /**
     * 设置当前用户上下文（收藏功能依赖 UserContext/TenantContext ThreadLocal）。
     * 必须在每个测试方法前调用，否则 Service 会抛 UNAUTHORIZED。
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
        FileNode node = new FileNode();
        node.setTenantId(tenantId);
        node.setParentId(0L);
        node.setNodeType(1);
        node.setName(name);
        node.setPath("/" + name);
        node.setFileSize(1024L);
        node.setContentType("text/plain");
        // 从文件名提取后缀，使测试数据更真实
        int dotIdx = name.lastIndexOf(".");
        node.setSuffix(dotIdx > 0 ? name.substring(dotIdx + 1) : null);
        node.setStatus(status);
        node.setUploadStatus(2);
        node.setUploaderId(ownerId);
        node.setOwnerId(ownerId);
        node.setVersion(0);
        fileNodeMapper.insert(node);
        return node;
    }
}
