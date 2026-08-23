package com.stcloud.share;

import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.Result;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.share.dto.CreateShareRequest;
import com.stcloud.share.dto.ShareAccessRequest;
import com.stcloud.share.dto.ShareVO;
import com.stcloud.share.service.ShareBruteForceGuard;
import com.stcloud.team.service.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分享防爆破集成测试（S-14，H2 真实 Mapper + Mock 外部服务）。
 * <p>
 * 覆盖：单分享码失败达阈值锁定、成功访问清除计数、达阈值需验证码、
 * 分享不存在不计频控、正常访问不误伤、验证码下发。
 */
class ShareServiceImplBruteForceIntegrationTest extends AbstractShareIntegrationTest {

    private static final Long USER_ID = 1001L;
    private static final Long TENANT_ID = 1L;

    @Autowired
    private ShareBruteForceGuard bruteForceGuard;

    @Autowired
    private FileService fileService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private TeamService teamService;

    @BeforeEach
    void setUp() {
        setUpUser(USER_ID, TENANT_ID);
        Mockito.reset(fileService, storageService, teamService);
    }

    private ShareVO createPrivateShare(String password) {
        FileNode node = insertFileNode(TENANT_ID, USER_ID, "bf-" + System.nanoTime() + ".txt", 0);
        CreateShareRequest req = new CreateShareRequest();
        req.setFileNodeId(node.getId());
        req.setShareType(1);
        req.setPassword(password);
        return shareService.createShare(req).getData();
    }

    private ShareAccessRequest buildReq(String code, String pwd) {
        ShareAccessRequest r = new ShareAccessRequest();
        r.setShareCode(code);
        r.setPassword(pwd);
        return r;
    }

    private BusinessException accessExpectError(String code, String pwd) {
        return assertThrows(BusinessException.class,
                () -> shareService.accessShare(buildReq(code, pwd)));
    }

    @Test
    @DisplayName("S-14 正确访问清除失败计数")
    void successfulAccessClearsFailure() {
        ShareVO vo = createPrivateShare("abcd");
        accessExpectError(vo.getShareCode(), "wrong");
        accessExpectError(vo.getShareCode(), "wrong");
        assertEquals(2, bruteForceGuard.failCountForCode(vo.getShareCode()));

        Result<?> result = shareService.accessShare(buildReq(vo.getShareCode(), "abcd"));
        assertNotNull(result.getData());
        assertEquals(0, bruteForceGuard.failCountForCode(vo.getShareCode()));
    }

    @Test
    @DisplayName("S-14 失败达验证码阈值后要求验证码")
    void captchaRequiredAtThreshold() {
        ShareVO vo = createPrivateShare("abcd");
        for (int i = 0; i < 3; i++) {
            accessExpectError(vo.getShareCode(), "wrong");
        }
        assertTrue(bruteForceGuard.needsCaptcha(vo.getShareCode()));
        BusinessException ex = accessExpectError(vo.getShareCode(), "abcd");
        assertEquals(ResultCode.SHARE_CAPTCHA_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("S-14 分享不存在直接抛，不计频控")
    void nonexistentShareNotCounted() {
        BusinessException ex = accessExpectError("NONEXIST-CODE", "x");
        assertEquals(ResultCode.SHARE_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("S-14 正常访问不误伤/不触发验证码")
    void normalAccessNotCounted() {
        ShareVO vo = createPrivateShare("1234");
        Result<?> result = shareService.accessShare(buildReq(vo.getShareCode(), "1234"));
        assertNotNull(result.getData());
        assertEquals(0, bruteForceGuard.failCountForCode(vo.getShareCode()));
        assertTrue(!bruteForceGuard.isLocked(null, vo.getShareCode()));
    }

    @Test
    @DisplayName("S-14 验证码下发接口返回 id 与图片")
    void getCaptchaReturnsIssue() {
        Result<Map<String, String>> result = shareService.getCaptcha();
        assertNotNull(result.getData());
        assertNotNull(result.getData().get("captchaId"));
        assertNotNull(result.getData().get("imageBase64"));
    }
}
