package com.stcloud.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.admin.dto.CreateSpeedLimitRequest;
import com.stcloud.admin.dto.SpeedLimitVO;
import com.stcloud.admin.service.SpeedLimitManageService;
import com.stcloud.common.entity.SysRateLimit;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.mapper.SysRateLimitMapper;
import com.stcloud.common.ratelimit.RateLimitProperties;
import com.stcloud.common.ratelimit.SpeedLimitCache;
import com.stcloud.common.response.ResultCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SpeedLimitManageServiceImpl implements SpeedLimitManageService {

    @Resource
    private SysRateLimitMapper rateLimitMapper;

    @Resource
    private SpeedLimitCache speedLimitCache;

    @Autowired(required = false)
    private StringRedisTemplate redis;
    @Autowired(required = false)
    private RateLimitProperties rateLimitProperties;

    @Override
    public List<SpeedLimitVO> listRules() {
        List<SysRateLimit> rules = rateLimitMapper.selectList(
                new LambdaQueryWrapper<SysRateLimit>().orderByDesc(SysRateLimit::getCreatedAt));
        return rules.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public SpeedLimitVO getRule(Long id) {
        SysRateLimit rule = rateLimitMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "限速规则不存在");
        }
        return toVO(rule);
    }

    @Override
    @Transactional
    public SpeedLimitVO createRule(CreateSpeedLimitRequest request) {
        validate(request);
        SysRateLimit rule = new SysRateLimit();
        applyRequest(rule, request);
        rule.setEnabled(request.getEnabled() != null ? request.getEnabled() : 1);
        rateLimitMapper.insert(rule);
        evictAndPublish();
        log.info("创建限速规则: {}", rule.getRuleName());
        return toVO(rule);
    }

    @Override
    @Transactional
    public SpeedLimitVO updateRule(Long id, CreateSpeedLimitRequest request) {
        SysRateLimit rule = rateLimitMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "限速规则不存在");
        }
        validate(request);
        applyRequest(rule, request);
        if (request.getEnabled() != null) {
            rule.setEnabled(request.getEnabled());
        }
        rateLimitMapper.updateById(rule);
        evictAndPublish();
        log.info("更新限速规则: id={}", id);
        return toVO(rule);
    }

    @Override
    @Transactional
    public void deleteRule(Long id) {
        SysRateLimit rule = rateLimitMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "限速规则不存在");
        }
        rateLimitMapper.deleteById(id);
        evictAndPublish();
        log.info("删除限速规则: id={}", id);
    }

    @Override
    @Transactional
    public void toggleRule(Long id) {
        SysRateLimit rule = rateLimitMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "限速规则不存在");
        }
        rule.setEnabled(rule.getEnabled() == 1 ? 0 : 1);
        rateLimitMapper.updateById(rule);
        evictAndPublish();
        log.info("切换限速规则状态: id={}, enabled={}", id, rule.getEnabled());
    }

    /** 本机内存失效立即执行；Redis 版本号与广播放到事务提交后，避免回滚仍递增版本。 */
    private void evictAndPublish() {
        speedLimitCache.evict();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 提交后递增版本并再次失效，覆盖「事务内读请求把提交前规则写回缓存」的窗口
                    publishInvalidation();
                }
            });
        } else {
            publishInvalidation();
        }
    }

    /** 先递增版本再失效/广播，避免其他节点缓存「新规则 + 旧版本」；Redis 不可用时只失效本机。 */
    private void publishInvalidation() {
        if (redis == null) {
            speedLimitCache.evict();
            return;
        }
        try {
            redis.opsForValue().increment(SpeedLimitCache.VERSION_KEY);
            speedLimitCache.evict();
            String channel = rateLimitProperties != null
                    ? rateLimitProperties.getInvalidationChannel() : "stcloud:rate-limit:evict";
            redis.convertAndSend(channel, "evict");
        } catch (RuntimeException e) {
            speedLimitCache.evict();
            log.warn("限速配置广播失败，依赖 TTL 兜底：{}", e.getMessage());
        }
    }

    private void validate(CreateSpeedLimitRequest request) {
        if (request.getScope() == null || (request.getScope() != 0 && request.getScope() != 1)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "限制范围只能为0(用户)或1(角色)");
        }
        if ((request.getUploadSpeedLimit() == null || request.getUploadSpeedLimit() == 0)
                && (request.getDownloadSpeedLimit() == null || request.getDownloadSpeedLimit() == 0)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传和下载限速不能同时为0或不限速");
        }
    }

    private void applyRequest(SysRateLimit rule, CreateSpeedLimitRequest request) {
        rule.setRuleName(request.getRuleName());
        rule.setScope(request.getScope());
        rule.setTargetId(request.getTargetId());
        rule.setTargetCode(request.getTargetCode());
        rule.setTargetName(request.getTargetName());
        rule.setUploadSpeedLimit(request.getUploadSpeedLimit() != null ? request.getUploadSpeedLimit() : 0);
        rule.setDownloadSpeedLimit(request.getDownloadSpeedLimit() != null ? request.getDownloadSpeedLimit() : 0);
        rule.setDescription(request.getDescription());
    }

    private SpeedLimitVO toVO(SysRateLimit rule) {
        SpeedLimitVO vo = new SpeedLimitVO();
        vo.setId(rule.getId());
        vo.setRuleName(rule.getRuleName());
        vo.setScope(rule.getScope());
        vo.setTargetId(rule.getTargetId());
        vo.setTargetCode(rule.getTargetCode());
        vo.setTargetName(rule.getTargetName());
        vo.setUploadSpeedLimit(rule.getUploadSpeedLimit());
        vo.setDownloadSpeedLimit(rule.getDownloadSpeedLimit());
        vo.setEnabled(rule.getEnabled());
        vo.setDescription(rule.getDescription());
        vo.setCreatedAt(rule.getCreatedAt());
        vo.setUpdatedAt(rule.getUpdatedAt());
        return vo;
    }
}