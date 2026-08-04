package com.stcloud.admin.service;

import com.stcloud.admin.dto.CreateSpeedLimitRequest;
import com.stcloud.admin.dto.SpeedLimitVO;

import java.util.List;

public interface SpeedLimitManageService {

    List<SpeedLimitVO> listRules();

    SpeedLimitVO getRule(Long id);

    SpeedLimitVO createRule(CreateSpeedLimitRequest request);

    SpeedLimitVO updateRule(Long id, CreateSpeedLimitRequest request);

    void deleteRule(Long id);

    void toggleRule(Long id);
}