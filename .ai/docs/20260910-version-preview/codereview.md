# Code Review 记录：历史版本预览

> Task: `20260910-version-preview`｜reviewer / review｜日期：2026-09-10
> 审查范围：`st-preview` 3 个主文件 + 1 个测试文件 + 测试 schema；`st-web` 4 个前端文件

## 一、结论

通过。无阻塞问题；发现 1 个测试隔离缺陷，已在实现阶段修复并复验。

## 二、审查项

| 维度 | 检查内容 | 结果 |
|------|---------|------|
| 接口契约 | 新端点路径/参数与 design.md 一致；旧端点路径与返回结构未变 | 通过 |
| 边界与复用 | `preview()` 与 `previewVersion()` 共用 `dispatchByStorage`，未复制类型分派逻辑 | 通过 |
| 行为兼容 | 当前版本预览走同一分派；图片缩略图 key 保持 `thumbnails/{nodeId}/{size}.jpg` 不变 | 通过 |
| 前端复用 | 版本模式复用 `PreviewModal` 渲染与图片工具栏，未另写预览组件 | 通过 |
| 组件契约 | `PreviewTarget` 集中定义，`FileBrowserDialogs` / `useFileDialogs` 类型一致 | 通过 |
| 层级 | 版本预览层 `z-[60]` 高于历史版本弹窗 `z-50` | 通过 |
| 死代码 | 未新增未使用常量/方法；`getVideoPreview` 仍被 `/video` 端点使用 | 通过 |

## 三、问题与处理

| 编号 | 级别 | 问题 | 处理 |
|------|------|------|------|
| CR-1 | 中 | `FileService` 等外部依赖是 Spring 单例 Mock，`PreviewVersionIntegrationTest` 中「无权限」用例 stub 的抛异常会残留，污染同 JVM 后续用例（首轮 4 个用例误报 403，另 1 个跨测试类被上个类的 `headObject` 桩影响） | 已修复：`@AfterEach` 统一 `reset(fileService, s3Client, s3Presigner, storageService)`；图片用例显式桩定 `headObject` 返回存在。复验 14 用例全绿 |

## 四、观察项（非阻塞，未修改）

- `PreviewServiceImpl.dispatchByStorage` 末尾的 `OFFICE_TYPES` 分支与 default 分支返回相同结果，属既有写法（重构前即如此），本次按最小改动保留。
- `PreviewModal` 现在承担"当前版本 + 历史版本"两种取数路径，文件较长（约 520 行）；若后续接入更多来源，建议拆分为独立取数 hook，本次不做。
