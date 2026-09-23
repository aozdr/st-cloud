package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.response.Result;
import com.stcloud.team.dto.FileWatchStateVO;
import com.stcloud.team.dto.FileWatchVO;

/** 当前登录主体的文件关注管理。 */
public interface FileWatchService {

    Result<FileWatchStateVO> state(Long nodeId);

    Result<FileWatchStateVO> watch(Long nodeId);

    Result<Void> unwatch(Long nodeId);

    Result<IPage<FileWatchVO>> list(int page, int size);
}
