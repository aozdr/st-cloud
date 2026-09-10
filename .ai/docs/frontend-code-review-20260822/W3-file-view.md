# W3 文件浏览视图 Code Review

> 审查人：主线程 reviewer（ox-alpha）/ 日期：2026-08-22 / 方式：静态人工审查（FileThumbnail 全文、FileBrowser 状态与数据加载区精读；布局组件与列表渲染未逐行深读）
> 范围：`pages/FileManager.tsx`(30行薄壳)、`components/file/` 视图类、`components/home/`、`components/layout/`

## 问题清单

| 编号 | 严重度 | 位置 | 问题 | 证据(代码片段) | 建议 |
|---|---|---|---|---|---|
| W3-1 | P1 | components/file/FileBrowser.tsx:277-300 | 目录列表请求无竞态防护（无 AbortController/序号）：快速连续切换文件夹时，慢的旧响应晚到会用 A 目录内容覆盖 B 目录显示 | `const res = await source.listFiles(parentId, page, pageSize); setFiles(records)` | 引入请求序号或 AbortController，仅采纳最新一次 |
| W3-2 | P1 | components/file/FileThumbnail.tsx:21-38 | 每个图片条目挂载即独立请求缩略图 URL：无并发上限、无内存缓存，大网格目录产生请求风暴，返回目录重复拉取；且失败兜底把 **7 天长效 accessToken** 直接放进 `<img src>`（进日志/Referer） | `.catch(() => setUrl(buildStreamUrl(file.id, { token: localStorage.getItem('accessToken') })))` | 模块级 LRU 缓存缩略图 URL；兜底改走一次性 download-token 流程 |
| W3-3 | P2 | FileBrowser.tsx（全文 1339 行） | 单文件承载状态/请求/12 个对话框编排/右键/拖拽/分页，可维护性差 | — | 拆分数据层（useFileList）与对话框编排层 |
| W3-4 | P2 | FileBrowser.tsx:75 | 列表无虚拟化，靠 pageSize≤200 控制规模；200 行 × 缩略图在低端设备仍可能卡顿 | `PAGE_SIZE_OPTIONS = [50,100,150,200]` | 如需更大页长再引入虚拟滚动 |

## 亮点

- zip 打包下载的 objectURL 创建/回收严格配对（FileBrowser.tsx:655/662），无泄漏
- 目录切换统一清理选区/焦点/搜索词/详情面板（:302-316），跨目录选中态残留问题不存在
- 模块级 folderScrollPositions 跨实例保留各目录滚动位置，体验细节到位
- 团队空间锁定语义（lockedBy+lockExpireAt）在前端集中一处判定并注释了后端权威

## 结论

交互骨架成熟，主要短板是数据加载层的竞态防护缺失与缩略图请求风暴，二者在大目录场景会同时恶化体验。

统计：P0×0　P1×2　P2×2
