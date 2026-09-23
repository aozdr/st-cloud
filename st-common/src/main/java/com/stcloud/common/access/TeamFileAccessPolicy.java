package com.stcloud.common.access;

/**
 * 团队文件访问策略。
 *
 * <p>公共层只暴露稳定的标量参数，避免搜索、通知等模块反向依赖团队实体或登录上下文。
 * 实现必须使用传入的租户和主体进行即时核权。</p>
 */
public interface TeamFileAccessPolicy {

    @FunctionalInterface
    interface ReadContext {
        boolean canView(Long nodeId);
    }

    /**
     * 判断指定主体当前是否仍是指定团队空间的有效成员。
     */
    boolean isActiveMember(Long tenantId, Long userId, Long spaceId);

    /**
     * 判断指定主体当前是否可以查看指定团队节点。
     */
    boolean canView(Long tenantId, Long userId, Long spaceId, Long nodeId);

    /**
     * 一次候选扫描内复用成员和祖先读取；最终返回前仍需调用无缓存的 canView 即时核权。
     */
    default ReadContext openReadContext(Long tenantId, Long userId, Long spaceId) {
        return nodeId -> canView(tenantId, userId, spaceId, nodeId);
    }
}
