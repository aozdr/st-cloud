package com.stcloud.core.service;

import com.stcloud.core.dto.RecycleItemVO;

import java.util.List;

/**
 * 回收站服务
 */
public interface RecycleBinService {

    /**
     * 列出回收站中的文件
     */
    List<RecycleItemVO> listRecycleBin();

    /**
     * 从回收站恢复文件
     */
    void restore(List<Long> nodeIds);

    /**
     * 永久删除回收站中的文件
     */
    void permanentDelete(List<Long> nodeIds);

    /**
     * 清空回收站
     */
    void emptyRecycleBin();

    /**
     * 查询超过保留期且为回收站根节点（父节点不在回收站）的节点 ID，供定时清理使用。
     */
    List<Long> findExpiredRecycleRoots();

    /**
     * 永久清理单个回收站节点（不依赖用户上下文，供定时任务调用）。
     */
    void purgeNode(Long nodeId);

    /**
     * 管理员强制永久删除正常态节点（同步异常数据清理用）：
     * 不校验归属，复用同一套 S3 物理对象/引用计数/配额/ES 索引清理逻辑。
     */
    void permanentDeleteAdmin(List<Long> nodeIds);
}
