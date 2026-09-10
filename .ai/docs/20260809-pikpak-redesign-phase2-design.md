# 技术设计 - PikPak 风格重做阶段二

> 关联：PRD §4.2、uiSpec 阶段二
> 约束：ffmpeg 不可用，视频帧提取留作后续，本期视频仅前端播放按钮叠加

## 1. 改动范围

### 前端（4 文件）
| 文件 | 改动 |
|------|------|
| FileToolbar.tsx | 工具栏 bg-surface/80 -> bg-bg，border-b -> shadow 分隔 |
| FileManager.tsx | 顶部条 bg-surface/80 -> bg-bg |
| FileGrid.tsx | GridThumbnail 增加文件夹封面加载 + 视频播放按钮 |
| types/index.ts | FileNode 增加可选 coverUrl 字段（前端缓存用） |

### 后端（3 文件）
| 文件 | 改动 |
|------|------|
| FileNodeMapper.java | 新增 selectFirstImageInFolder 查询 |
| PreviewService.java | 新增 getFolderCoverUrl 方法 |
| PreviewController.java | 新增 GET /api/preview/folder/{nodeId}/cover |
| PreviewServiceImpl.java | 实现 getFolderCoverUrl |

## 2. 后端设计

### 2.1 文件夹代表图查询（FileNodeMapper）
```java
@Select("SELECT * FROM file_node WHERE parent_id = #{folderId} AND node_type = 1 " +
        "AND suffix IN ('jpg','jpeg','png','gif','webp','bmp','svg') " +
        "AND status = 0 AND deleted = 0 AND upload_status = 2 ORDER BY updated_at DESC LIMIT 1")
FileNode selectFirstImageInFolder(@Param("folderId") Long folderId);
```

### 2.2 PreviewService.getFolderCoverUrl
```java
String getFolderCoverUrl(Long folderId);
```
逻辑：校验 folderId 可访问 -> selectFirstImageInFolder -> 有则返回 getThumbnailUrl(imageNodeId, "sm") -> 无则返回 null

### 2.3 PreviewController 端点
```java
@GetMapping("/folder/{nodeId}/cover")
public Result<String> getFolderCover(@PathVariable Long nodeId) {
    return Result.success(previewService.getFolderCoverUrl(nodeId));
}
```

## 3. 前端设计

### 3.1 工具栏视觉层次修正
FileToolbar.tsx:47: `bg-surface/80 backdrop-blur-md border-b border-border/80` -> `bg-bg border-b border-border/60 shadow-[0_1px_0_rgba(0,0,0,0.04)]`
FileManager.tsx:24: 同样 `bg-surface/80` -> `bg-bg`

### 3.2 3D 文件夹封面
GridThumbnail 中文件夹分支：调用 `/preview/folder/{id}/cover` 获取封面 URL，有则渲染 3D 封面（back + cover img + front），无则 fallback FileTypeIcon。

3D 封面 CSS 结构（PikPak folder-cover 简化版）：
```tsx
<div className="folder-cover-3d">  {/* 透视容器 */}
  <div className="back" />           {/* 背层 */}
  <div className="cover">            {/* 封面图 16:9 */}
    {coverUrl && <img src={coverUrl} className="object-cover w-full h-full" />}
  </div>
  <div className="front" />          {/* 前层文件夹盖 */}
</div>
```
CSS 用 transform 3D 透视模拟立体感。

### 3.3 视频播放按钮
GridThumbnail 中视频文件：FileTypeIcon + 居中播放按钮（半透明圆形 + 白色三角），点击触发 onDoubleClick（进入预览播放器）。

```tsx
{isVideo(file) && (
  <button className="absolute inset-0 flex items-center justify-center" onClick={...}>
    <span className="w-12 h-12 rounded-full bg-black/50 flex items-center justify-center">
      <Play className="w-5 h-5 text-white" fill="currentColor" />
    </span>
  </button>
)}
```

## 4. 安全
文件夹代表图接口需校验 folderId 可访问性（复用 fileService.validateAccessible），防止越权访问他人文件夹内容。

## 5. 测试
- 工具栏不再纯白（bg-bg 非 bg-surface）
- 文件夹有图片子文件时显示 3D 封面
- 文件夹无图片子文件时 fallback 图标
- 视频显示播放按钮
- 前后端编译通过
- 越权访问文件夹封面返回 403
