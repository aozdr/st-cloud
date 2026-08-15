package com.stcloud.share;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.Result;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.share.dto.CreateShareRequest;
import com.stcloud.share.dto.ShareAccessRequest;
import com.stcloud.share.dto.ShareAccessVO;
import com.stcloud.share.dto.ShareVO;
import com.stcloud.share.dto.UpdateShareRequest;
import com.stcloud.share.entity.FileShare;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分享可选过期时间集成测试（H2 真实 Mapper + Mock 外部服务）。
 * <p>
 * 覆盖：未来时间合法 / 过去时间 BAD_REQUEST / clearExpireAt 清除过期 /
 * 访问过期拒绝（SHARE_EXPIRED）/ 过期校验优先于提取码校验。
 */
class ShareServiceImplExpiryIntegrationTest extends AbstractShareIntegrationTest {

    private static final Long USER_ID = 1001L;
    private static final Long TENANT_ID = 1L;

    private FileNode fileNode;

    @BeforeEach
    void setUp() {
        setUpUser(USER_ID, TENANT_ID);
        fileNode = insertFileNode(TENANT_ID, USER_ID, "share-demo.txt", 0);
    }

    private LocalDateTime future() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(7);
    }

    private LocalDateTime past() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
    }

    private ShareVO createShare(LocalDateTime expireAt) {
        CreateShareRequest req = new CreateShareRequest();
        req.setFileNodeId(fileNode.getId());
        req.setShareType(0);
        req.setExpireAt(expireAt);
        Result<ShareVO> result = shareService.createShare(req);
        return result.getData();
    }

    private FileShare loadShare(Long shareId) {
        return fileShareMapper.selectById(shareId);
    }

    @Test
    @DisplayName("S1 创建有限期分享：未来时间合法，VO/DB expireAt 非空")
    void createWithFutureExpireAt() {
        LocalDateTime expireAt = future();
        ShareVO vo = createShare(expireAt);
        assertEquals(expireAt, vo.getExpireAt());
        assertNotNull(loadShare(vo.getId()).getExpireAt());
    }

    @Test
    @DisplayName("S2 创建永久分享：expireAt 为 null 合法")
    void createPermanentShare() {
        ShareVO vo = createShare(null);
        assertNull(vo.getExpireAt());
        assertNull(loadShare(vo.getId()).getExpireAt());
    }

    @Test
    @DisplayName("S3 创建过去时间分享：抛 BAD_REQUEST")
    void createWithPastExpireAtRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> createShare(past()));
        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("过期时间必须晚于当前时间"));
    }

    @Test
    @DisplayName("S4 未过期分享访问成功")
    void accessBeforeExpiry() {
        ShareVO vo = createShare(future());
        ShareAccessRequest req = new ShareAccessRequest();
        req.setShareCode(vo.getShareCode());
        ShareAccessVO access = shareService.accessShare(req).getData();
        assertEquals(fileNode.getName(), access.getFileName());
    }

    @Test
    @DisplayName("S5 过期后访问被拒：抛 SHARE_EXPIRED")
    void accessAfterExpired() {
        ShareVO vo = createShare(future());
        fileShareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getId, vo.getId())
                .set(FileShare::getExpireAt, past()));

        ShareAccessRequest req = new ShareAccessRequest();
        req.setShareCode(vo.getShareCode());
        BusinessException ex = assertThrows(BusinessException.class, () -> shareService.accessShare(req));
        assertEquals(ResultCode.SHARE_EXPIRED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("S10 更新清除过期：clearExpireAt=true 后 DB expire_at 为 null")
    void updateClearExpireAt() {
        ShareVO vo = createShare(future());
        UpdateShareRequest req = new UpdateShareRequest();
        req.setClearExpireAt(true);
        shareService.updateShare(vo.getId(), req);
        assertNull(loadShare(vo.getId()).getExpireAt());
    }

    @Test
    @DisplayName("S14 clearExpireAt=false 不清除过期时间")
    void updateClearExpireAtFalseKeepsExpireAt() {
        ShareVO vo = createShare(future());
        UpdateShareRequest req = new UpdateShareRequest();
        req.setClearExpireAt(false);
        shareService.updateShare(vo.getId(), req);
        assertNotNull(loadShare(vo.getId()).getExpireAt());
    }

    @Test
    @DisplayName("S9 更新修改过期时间：新未来时间生效")
    void updateModifyExpireAt() {
        ShareVO vo = createShare(future());
        // 截断到秒，避免 H2 DATETIME 微秒精度截断导致断言失败
        LocalDateTime newExpireAt = future().plusDays(3).withNano(0);
        UpdateShareRequest req = new UpdateShareRequest();
        req.setExpireAt(newExpireAt);
        shareService.updateShare(vo.getId(), req);
        assertEquals(newExpireAt, loadShare(vo.getId()).getExpireAt());
    }

    @Test
    @DisplayName("S11 更新传过去时间：抛 BAD_REQUEST")
    void updateWithPastExpireAtRejected() {
        ShareVO vo = createShare(future());
        UpdateShareRequest req = new UpdateShareRequest();
        req.setExpireAt(past());
        BusinessException ex = assertThrows(BusinessException.class, () -> shareService.updateShare(vo.getId(), req));
        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("过期时间必须晚于当前时间"));
    }

    @Test
    @DisplayName("S13 私密分享过期校验优先于提取码校验")
    void expiredPrivateShareRejectedBeforePasswordCheck() {
        CreateShareRequest req = new CreateShareRequest();
        req.setFileNodeId(fileNode.getId());
        req.setShareType(1);
        req.setPassword("TEST1234");
        req.setExpireAt(future());
        ShareVO vo = shareService.createShare(req).getData();
        fileShareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getId, vo.getId())
                .set(FileShare::getExpireAt, past()));

        ShareAccessRequest access = new ShareAccessRequest();
        access.setShareCode(vo.getShareCode());
        access.setPassword("WRONG");
        BusinessException ex = assertThrows(BusinessException.class, () -> shareService.accessShare(access));
        assertEquals(ResultCode.SHARE_EXPIRED.getCode(), ex.getCode());
    }
}
