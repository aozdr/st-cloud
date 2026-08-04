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
}
