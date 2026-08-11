package com.stcloud.core.service.impl;

import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.entity.FileNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 收藏功能集成测试（H2 + 真实 MyBatis-Plus）。
 * <p>
 * 验证真实 SQL 执行、表结构、Mapper 映射、租户隔离——这些是 Mockito 单元测试无法覆盖的。
 * 若 file_favorite 表不存在，测试上下文启动即失败（schema.sql 执行报错）。
 */
@DisplayName("收藏功能集成测试")
class FavoriteServiceIntegrationTest extends AbstractIntegrationTest {

    // ==================== toggleFavorite ====================

    @Nested
    @DisplayName("切换收藏状态")
    class ToggleFavorite {

        @Test
        @DisplayName("新增收藏 - 返回 true 且数据库存在记录")
        void toggle_addFavorite() {
            setUpUser(100L, 1L);
            FileNode node = insertFileNode(1L, 100L, "report.txt", 0);

            boolean result = favoriteService.toggleFavorite(node.getId());

            assertTrue(result, "未收藏的文件，toggle 应返回 true");
            // 直接查库验证记录确实写入（不走 Service，验证真实 SQL）
            long count = fileFavoriteMapper.selectCount(null);
            assertEquals(1, count, "file_favorite 表应有 1 条记录");
        }

        @Test
        @DisplayName("取消收藏 - 返回 false 且记录已删除")
        void toggle_cancelFavorite() {
            setUpUser(100L, 1L);
            FileNode node = insertFileNode(1L, 100L, "report.txt", 0);
            favoriteService.toggleFavorite(node.getId()); // 先收藏

            boolean result = favoriteService.toggleFavorite(node.getId()); // 再取消

            assertFalse(result, "已收藏的文件，toggle 应返回 false");
            long count = fileFavoriteMapper.selectCount(null);
            assertEquals(0, count, "取消后 file_favorite 表应无记录");
        }

        @Test
        @DisplayName("文件不存在 - 抛 FILE_NOT_FOUND")
        void toggle_fileNotFound() {
            setUpUser(100L, 1L);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> favoriteService.toggleFavorite(999999L));
            assertEquals(ResultCode.FILE_NOT_FOUND.getCode(), ex.getCode());
        }

        @Test
        @DisplayName("文件在回收站 - 抛 FILE_NOT_FOUND")
        void toggle_fileInRecycleBin() {
            setUpUser(100L, 1L);
            FileNode node = insertFileNode(1L, 100L, "deleted.txt", 1); // status=1 回收站

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> favoriteService.toggleFavorite(node.getId()));
            assertEquals(ResultCode.FILE_NOT_FOUND.getCode(), ex.getCode());
        }

        @Test
        @DisplayName("未登录 - 抛 UNAUTHORIZED")
        void toggle_notAuthenticated() {
            // 不调用 setUpUser，UserContext 为空
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> favoriteService.toggleFavorite(1L));
            assertEquals(ResultCode.UNAUTHORIZED.getCode(), ex.getCode());
        }
    }

    // ==================== listFavorites ====================

    @Nested
    @DisplayName("收藏列表")
    class ListFavorites {

        @Test
        @DisplayName("返回收藏文件列表 - 验证 JOIN SQL 正确")
        void list_returnsFavoriteFiles() {
            setUpUser(100L, 1L);
            FileNode node = insertFileNode(1L, 100L, "doc.pdf", 0);
            favoriteService.toggleFavorite(node.getId());

            List<FileNodeVO> result = favoriteService.listFavorites();

            assertEquals(1, result.size(), "应有 1 个收藏文件");
            FileNodeVO vo = result.get(0);
            assertEquals(node.getId(), vo.getId());
            assertEquals("doc.pdf", vo.getName());
            assertEquals("pdf", vo.getSuffix());
        }

        @Test
        @DisplayName("过滤回收站文件 - 回收站文件不出现在收藏列表")
        void list_filtersRecycleBinFiles() {
            setUpUser(100L, 1L);
            FileNode normalNode = insertFileNode(1L, 100L, "normal.txt", 0);
            FileNode recycledNode = insertFileNode(1L, 100L, "recycled.txt", 1);

            // 收藏两个文件（收藏时回收站文件会抛异常，所以先收藏再改状态）
            favoriteService.toggleFavorite(normalNode.getId());

            // 直接插入回收站文件的收藏记录（绕过 Service 校验）
            com.stcloud.core.entity.FileFavorite fav = new com.stcloud.core.entity.FileFavorite();
            fav.setUserId(100L);
            fav.setFileNodeId(recycledNode.getId());
            fav.setTenantId(1L);
            fileFavoriteMapper.insert(fav);

            List<FileNodeVO> result = favoriteService.listFavorites();

            assertEquals(1, result.size(), "回收站文件不应出现在收藏列表");
            assertEquals(normalNode.getId(), result.get(0).getId());
        }

        @Test
        @DisplayName("空收藏列表 - 返回空列表而非 null")
        void list_empty() {
            setUpUser(100L, 1L);

            List<FileNodeVO> result = favoriteService.listFavorites();

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // ==================== listFavoriteIds ====================

    @Nested
    @DisplayName("收藏ID列表")
    class ListFavoriteIds {

        @Test
        @DisplayName("返回收藏文件ID列表")
        void listIds_returnsFavoriteIds() {
            setUpUser(100L, 1L);
            FileNode node1 = insertFileNode(1L, 100L, "a.txt", 0);
            FileNode node2 = insertFileNode(1L, 100L, "b.txt", 0);
            favoriteService.toggleFavorite(node1.getId());
            favoriteService.toggleFavorite(node2.getId());

            List<Long> ids = favoriteService.listFavoriteIds();

            assertEquals(2, ids.size(), "应有 2 个收藏 ID");
            assertTrue(ids.contains(node1.getId()));
            assertTrue(ids.contains(node2.getId()));
        }
    }

    // ==================== 租户隔离 ====================

    @Nested
    @DisplayName("租户隔离")
    class TenantIsolation {

        @Test
        @DisplayName("租户A的收藏对租户B不可见")
        void tenantIsolation() {
            // 租户 1 用户收藏文件
            setUpUser(100L, 1L);
            FileNode node = insertFileNode(1L, 100L, "tenant1-file.txt", 0);
            favoriteService.toggleFavorite(node.getId());
            assertEquals(1, favoriteService.listFavoriteIds().size(), "租户1应看到1个收藏");

            // 切换到租户 2
            setUpUser(200L, 2L);

            List<Long> ids = favoriteService.listFavoriteIds();
            assertTrue(ids.isEmpty(), "租户2不应看到租户1的收藏");
        }
    }
}
