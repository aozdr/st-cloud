# 收藏功能完善 - 设计文档

> 经前后端程序设计 + 四方设计评审后输出

## 设计评审关键决策

1. **收藏排序方案**：前端当前页排序置顶，后端不改 listDirectory 查询。理由：收藏量通常少，分页 50/页，前端排序够用且避免 JOIN 性能影响。
2. **收藏页 FileBrowser 复用**：通过 favoriteFileSource 适配 FileSource 接口。收藏页禁用新建文件夹/上传/拖拽移动等无意义操作。
3. **后端分页接口**：新增 `GET /favorite/page`，原 `/favorite/list` 保留给首页。
4. **权限校验**：toggleFavorite 增加 validateAccessible 调用，复用 FileService 现有方法。

---

## 后端设计

### B1. FavoriteController 新增分页接口

```java
@Operation(summary = "收藏列表（分页）")
@GetMapping("/page")
public Result<IPage<FileNodeVO>> pageFavorites(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "50") int size) {
    return Result.success(favoriteService.pageFavorites(page, size));
}
```

### B2. FavoriteService 新增方法

```java
IPage<FileNodeVO> pageFavorites(int page, int size);
```

### B3. FavoriteServiceImpl 实现

- 新增 `pageFavorites`：复用 `selectFavoriteNodes` 获取全量后手动分页，或新增 Mapper 分页查询
- 采用 Mapper 分页查询（性能更好）：

```sql
SELECT fn.* FROM file_favorite fav
JOIN file_node fn ON fav.file_node_id = fn.id AND fn.deleted = 0 AND fn.status = 0
WHERE fav.user_id = #{userId} AND fav.tenant_id = #{tenantId} AND fav.deleted = 0
ORDER BY fav.created_at DESC
```

- `toggleFavorite` 增加权限校验：调用 `fileService.validateAccessible(nodeId)`

### B4. FileFavoriteMapper 新增分页方法

```java
@Select("...（同上 SQL）...")
IPage<FileNode> selectFavoriteNodesPage(@Param("page") Page<FileNode> page,
    @Param("userId") Long userId, @Param("tenantId") Long tenantId);
```

### B5. 修改文件清单

| 文件 | 变更 |
|------|------|
| FavoriteController.java | 新增 pageFavorites 接口 |
| FavoriteService.java | 新增 pageFavorites 方法签名 |
| FavoriteServiceImpl.java | 实现 pageFavorites；toggleFavorite 增加权限校验 |
| FileFavoriteMapper.java | 新增 selectFavoriteNodesPage 分页查询 |

---

## 前端设计

### F1. 路由新增

`src/App.tsx` 新增：
```tsx
<Route path="favorites" element={<FavoritesPage />} />
```

### F2. FavoritesPage 页面

`src/pages/FavoritesPage.tsx`：
- 复用 FileBrowser，source 传 favoriteFileSource
- categoryLabel="我的收藏"
- onNavigateFolder：跳转到文件管理器对应目录（navigate(`/files/${node.id}`)）
- enableShare、enableVersions

### F3. favoriteFileSource

`src/lib/fileSource.ts` 新增：
```ts
export function favoriteFileSource(): FileSource {
  const base = personalFileSource;
  return {
    ...base,
    listFiles: async (_parentId, page, size) => {
      return api.get('/favorite/page', { params: { page, size } });
    },
    // createFolder/move/copy 在收藏页无意义，保留 base 实现但 UI 不暴露
  };
}
```

### F4. 侧边栏导航项

`src/components/layout/Sidebar.tsx` mainNav 新增：
```ts
{ to: '/favorites', icon: Star, label: '我的收藏', end: false },
```
位置：全部文件下方、我的分享上方。需 import Star。

### F5. FileBrowser 收藏排序

`src/components/file/FileBrowser.tsx` 的 `sortedFiles` useMemo 中，在现有排序后增加收藏置顶：
```ts
const sortedFiles = useMemo(() => {
  // ... 现有排序逻辑 ...
  // 收藏项置顶（同类型内）
  return arr.sort((a, b) => {
    const aFav = checkFav(a.id) ? 1 : 0;
    const bFav = checkFav(b.id) ? 1 : 0;
    if (aFav !== bFav) return bFav - aFav;
    // 保持原有排序
    return 0;
  });
}, [...]);
```

注意：在文件夹/文件分组后、用户排序字段前插入收藏比较。

### F6. 首页"查看全部"链接

`src/pages/HomePage.tsx` 收藏区块标题旁增加：
```tsx
<button onClick={() => navigate('/favorites')} className="...">
  查看全部 <ChevronRight />
</button>
```

### F7. 清理遗留代码

- 删除 `src/lib/favorites.ts`
- 确认无引用（已验证）

### F8. 修改文件清单

| 文件 | 变更 |
|------|------|
| src/App.tsx | 新增 /favorites 路由 |
| src/pages/FavoritesPage.tsx | 新增收藏页面 |
| src/lib/fileSource.ts | 新增 favoriteFileSource |
| src/components/layout/Sidebar.tsx | mainNav 新增收藏导航项 |
| src/components/file/FileBrowser.tsx | sortedFiles 增加收藏置顶排序 |
| src/pages/HomePage.tsx | 收藏区块增加"查看全部"链接 |
| src/lib/favorites.ts | 删除 |

---

## 接口契约

### 新增接口

| 方法 | 路径 | 说明 | 请求参数 | 响应 |
|------|------|------|----------|------|
| GET | /api/favorite/page | 收藏列表分页 | page(默认1), size(默认50) | IPage<FileNodeVO> |

### 现有接口（不变）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/favorite/{nodeId} | 切换收藏 |
| GET | /api/favorite/list | 收藏全量列表（首页用） |
| GET | /api/favorite/ids | 收藏ID列表（轻量） |
