package com.stcloud.admin.service.impl;

import com.stcloud.admin.dto.StatsVO;
import com.stcloud.admin.mapper.StatsMapper;
import com.stcloud.admin.service.StatsService;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class StatsServiceImpl implements StatsService {

    @Resource
    private StatsMapper statsMapper;

    @Override
    public StatsVO getStats() {
        if (!UserContext.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "需要管理员权限");
        }
        StatsVO vo = new StatsVO();
        vo.setTotalUsers(statsMapper.countUsers());
        vo.setActiveUsers(statsMapper.countActiveUsers());
        vo.setTotalFiles(statsMapper.countFiles());
        vo.setTotalStorageUsed(statsMapper.sumStorageUsed());
        vo.setTotalShares(statsMapper.countShares());
        vo.setTotalTeams(statsMapper.countTeams());
        vo.setCloudTotalCapacity(statsMapper.getCloudTotalCapacity());
        vo.setCloudStorageUsed(statsMapper.sumCloudStorageUsed());
        return vo;
    }
}
