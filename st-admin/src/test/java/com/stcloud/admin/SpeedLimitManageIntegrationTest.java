package com.stcloud.admin;

import com.stcloud.admin.dto.CreateSpeedLimitRequest;
import com.stcloud.admin.dto.SpeedLimitVO;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * st-admin 传输限速配置主路径集成测试：
 * 创建/列表/详情/更新/启停/删除 + 参数校验。
 */
@DisplayName("st-admin 传输限速配置集成测试")
class SpeedLimitManageIntegrationTest extends AbstractAdminIntegrationTest {

    @Test
    @DisplayName("创建限速规则后列表与详情可查询，默认启用")
    void createRule_thenListAndGet() {
        SpeedLimitVO created = speedLimitManageService.createRule(
                buildRequest("用户全局限速", 0, 1024, 2048));

        assertNotNull(created.getId());
        assertEquals(1, created.getEnabled());

        List<SpeedLimitVO> rules = speedLimitManageService.listRules();
        assertTrue(rules.stream().anyMatch(r ->
                r.getId().equals(created.getId()) && "用户全局限速".equals(r.getRuleName())));

        SpeedLimitVO got = speedLimitManageService.getRule(created.getId());
        assertEquals(1024, got.getUploadSpeedLimit());
        assertEquals(2048, got.getDownloadSpeedLimit());
        assertEquals("用户100", got.getTargetName());
    }

    @Test
    @DisplayName("更新限速规则后变更持久化")
    void updateRule_persistsChanges() {
        SpeedLimitVO created = speedLimitManageService.createRule(
                buildRequest("初始规则", 0, 1024, 2048));

        CreateSpeedLimitRequest update = buildRequest("更新后规则", 1, 4096, 512);
        update.setTargetCode("role_admin");
        SpeedLimitVO updated = speedLimitManageService.updateRule(created.getId(), update);

        assertEquals("更新后规则", updated.getRuleName());
        assertEquals(4096, updated.getUploadSpeedLimit());
        assertEquals(512, updated.getDownloadSpeedLimit());

        SpeedLimitVO got = speedLimitManageService.getRule(created.getId());
        assertEquals("更新后规则", got.getRuleName());
        assertEquals("role_admin", got.getTargetCode());
    }

    @Test
    @DisplayName("启停切换限速规则：enabled 在 0/1 之间翻转")
    void toggleRule_flipsEnabled() {
        SpeedLimitVO created = speedLimitManageService.createRule(
                buildRequest("可启停规则", 0, 1024, 1024));

        speedLimitManageService.toggleRule(created.getId());
        assertEquals(0, speedLimitManageService.getRule(created.getId()).getEnabled());

        speedLimitManageService.toggleRule(created.getId());
        assertEquals(1, speedLimitManageService.getRule(created.getId()).getEnabled());
    }

    @Test
    @DisplayName("删除限速规则后列表为空、详情不可查")
    void deleteRule_removesRule() {
        SpeedLimitVO created = speedLimitManageService.createRule(
                buildRequest("待删除规则", 0, 1024, 1024));

        speedLimitManageService.deleteRule(created.getId());

        List<SpeedLimitVO> rules = speedLimitManageService.listRules();
        assertTrue(rules.stream().noneMatch(r -> r.getId().equals(created.getId())));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> speedLimitManageService.getRule(created.getId()));
        assertTrue(ex.getMessage().contains("限速规则不存在"));
    }

    @Test
    @DisplayName("参数校验：非法 scope 或上传/下载同时为 0 均拒绝")
    void createRule_validation_rejectsInvalidInput() {
        // scope 只能为 0(用户) 或 1(角色)
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> speedLimitManageService.createRule(buildRequest("非法范围", 2, 1024, 1024)));
        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex1.getCode());

        // 上传与下载限速不能同时为 0
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> speedLimitManageService.createRule(buildRequest("双零限速", 0, 0, 0)));
        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex2.getCode());
    }

    private CreateSpeedLimitRequest buildRequest(String name, Integer scope,
                                                 Integer upload, Integer download) {
        CreateSpeedLimitRequest request = new CreateSpeedLimitRequest();
        request.setRuleName(name);
        request.setScope(scope);
        request.setTargetId(100L);
        request.setTargetCode("user100");
        request.setTargetName("用户100");
        request.setUploadSpeedLimit(upload);
        request.setDownloadSpeedLimit(download);
        request.setDescription("测试规则");
        return request;
    }
}
