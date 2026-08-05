package com.stcloud.common.utils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.mapper.SysJwtSecretMapper;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtUtils 单元测试 - 密钥外置与下载令牌")
class JwtUtilsTest {

    @Mock
    private SysJwtSecretMapper jwtSecretMapper;

    @InjectMocks
    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        // 首次启动：DB 无记录 -> 随机生成并加密入库（insert 为 mock 空操作）
        lenient().when(jwtSecretMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        ReflectionTestUtils.setField(jwtUtils, "masterKey", "test-master-key-at-least-32-bytes-long-xx");
        ReflectionTestUtils.setField(jwtUtils, "expiration", 7_200_000L);
        ReflectionTestUtils.setField(jwtUtils, "refreshExpiration", 2_592_000_000L);
        ReflectionTestUtils.setField(jwtUtils, "downloadExpiration", 300_000L);
        jwtUtils.init();
    }

    @Test
    @DisplayName("下载令牌携带 type=download 声明")
    void downloadTokenHasTypeClaim() {
        String token = jwtUtils.generateDownloadToken(1L, 1L, "alice",
                List.of("user"), List.of("file:download"), 1);
        assertTrue(jwtUtils.validateToken(token));
        Claims claims = jwtUtils.parseToken(token);
        assertEquals("download", claims.get("type"));
        assertEquals("alice", claims.getSubject());
        assertEquals(1L, claims.get("userId", Long.class));
    }

    @Test
    @DisplayName("访问令牌不含 type=download")
    void accessTokenHasNoDownloadType() {
        String token = jwtUtils.generateToken(1L, 1L, "alice",
                List.of("user"), List.of("file:download"), 1);
        assertNull(jwtUtils.parseToken(token).get("type"));
    }

    @Test
    @DisplayName("非法 token 校验失败")
    void validateRejectsGarbage() {
        assertFalse(jwtUtils.validateToken("not-a-jwt"));
    }

    @Test
    @DisplayName("getUserId/getTenantId/getUsername 正确解析")
    void parseClaims() {
        String token = jwtUtils.generateDownloadToken(42L, 7L, "bob",
                List.of("admin"), List.of("file:download"), 3);
        assertEquals(42L, jwtUtils.getUserId(token));
        assertEquals(7L, jwtUtils.getTenantId(token));
        assertEquals("bob", jwtUtils.getUsername(token));
    }

    @Test
    @DisplayName("主密钥缺失或过短时启动 fail-fast")
    void failFastOnMissingMasterKey() {
        JwtUtils fresh = new JwtUtils(jwtSecretMapper);
        ReflectionTestUtils.setField(fresh, "masterKey", "short");
        assertThrows(IllegalStateException.class, fresh::init);
    }
}