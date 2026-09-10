# 程序设计文档：历史版本预览

> Task: `20260910-version-preview`｜规模：中型｜产出：executor（taskType=design）
> 依据模板 `.ai/templates/design-template.md`，输出标准 `docs/newList/ai-design-document-standard.md`
> 状态：待用户确认（遗留问题点见第十章，逐项拍板后才进入测试用例/实现）

# 一、需求分析

## 功能名称

历史版本预览

## 功能描述

对齐 `docs/PRD-云盘系统-v2.0.md` Story 6.1 未勾选项「支持预览历史版本」。当前「历史版本」弹窗只有列表与恢复，用户要看某个旧版本内容，必须先恢复再查看，恢复会改动当前版本。

```
用户：文件拥有者或对该文件有读权限的协作者
操作：文件右键「历史版本」→ 某个版本行点「预览」
系统行为：按该版本的对象存储路径生成预览，不读取、不改动当前版本
最终结果：弹窗内查看历史版本内容（图片/视频/音频/文本/PDF），可继续预览其它版本或恢复
```

不在本次范围：预览历史版本时编辑、下载历史版本、分享历史版本、Office 历史版本转换。

# 二、系统影响分析

## 影响模块

| 类型 | 模块 | 是否修改 |
|------|------|---------|
| 前端 | st-web（历史版本弹窗、预览弹窗、弹窗接线） | 是 |
| 后端 | st-preview（版本预览接口与分派逻辑） | 是 |
| 后端 | st-core（仅复用 `StorageService.generateDownloadUrl`、`VersionService`，不改代码） | 否 |
| 数据库 | 无表/字段/索引变化 | 否 |

## 影响文件预测

- 新增文件：
  - `st-preview/src/test/java/com/stcloud/preview/service/PreviewVersionIntegrationTest.java`（版本预览集成测试）
- 修改文件：
  - `st-preview/src/main/java/com/stcloud/preview/controller/PreviewController.java`（新增端点）
  - `st-preview/src/main/java/com/stcloud/preview/service/PreviewService.java`（新增方法声明）
  - `st-preview/src/main/java/com/stcloud/preview/service/impl/PreviewServiceImpl.java`（版本预览实现 + 分派重构）
  - `st-preview/src/test/resources/schema.sql`（补 `file_version` 建表，测试用）
  - `st-web/src/components/preview/PreviewModal.tsx`（版本预览模式）
  - `st-web/src/components/file/VersionHistoryDialog.tsx`（新增「预览」按钮）
  - `st-web/src/components/file/FileBrowserDialogs.tsx`（预览状态携带版本信息）
  - `.ai/knowledge/api-reference.md`（补端点，验收后）
  - `docs/PRD-云盘系统-v2.0.md`（Story 6.1 勾选，验收后）
- 删除文件：无

# 三、整体设计方案

```
VersionHistoryDialog「预览」
  → FileBrowserDialogs 打开 PreviewModal(versionId, versionNum)
  → GET /api/preview/{nodeId}/version/{versionId}
  → st-preview: 校验 node 可访问 + version 归属 node
  → 按 suffix 分派（图片缩略图 / 音视频 PDF 预签名 URL / 文本内容 / 不支持）
  → PreviewResultVO → PreviewModal 按版本模式渲染
```

关键决策：版本预览放在 `st-preview`，不放在 `st-core` 的 `VersionController`。理由：类型分派、缩略图生成、文本截断逻辑都在 `PreviewServiceImpl`，放 `st-preview` 可直接复用；`st-core` 反向依赖 `st-preview` 的 DTO 不成立（`st-preview` 依赖 `st-core`）。

# 四、前端设计

- 页面设计：不新增页面。版本预览复用现有 `PreviewModal` 全屏层，`VersionHistoryDialog` 保持打开，预览层覆盖在其上（预览层 z-index 更高）。
- 组件设计：
  - `VersionHistoryDialog`：新增 prop `onPreview(version: FileVersionVO)`；每行新增「预览」按钮，仅 `current !== true` 的行显示；`current` 行沿用文件本身的预览入口。
  - `FileBrowserDialogs`：`preview` 状态类型扩展为 `{ files, index, versionId?, versionNum? }`，现有文件预览不受影响（字段可选）。
  - `PreviewModal`：新增可选 props `versionId`、`versionNum`。版本模式下：
    - 数据源改为 `GET /preview/{nodeId}/version/{versionId}`；
    - 图片用返回的缩略图 URL，音视频/PDF 用返回的预签名 URL，文本用返回的 `content`；
    - 短路 OnlyOffice 跳转分支（防止历史版本被跳去编辑器查看当前版本内容）；
    - 禁用左右切换、幻灯片、音频队列；不写入「最近文件」；隐藏下载按钮；
    - 标题区显示「历史版本 V{n}」角标；`unsupported` 显示「该类型历史版本暂不支持在线预览」。
- 状态设计：版本信息随预览状态传递，不新增全局状态。
- UI 规范：按钮、角标复用 `PreviewModal` / `VersionHistoryDialog` 现有样式，不新增设计令牌。

# 五、后端设计

## API 设计

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/preview/{nodeId}/version/{versionId}` | 获取指定历史版本的预览结果 |

- 鉴权：沿用 `PreviewController` 类级 `@PreAuthorize("isAuthenticated()")`
- 返回：`Result<PreviewResultVO>`（`type` / `url` / `content` / `suffix` / `status`）
- 兼容：新增端点，不改动 `/api/preview/{nodeId}`、`/thumbnail`、`/video` 的路径与返回结构

## 业务流程

1. `getFileNode(nodeId)`：复用现有校验（不存在或已删除 → `FILE_NOT_FOUND`；`fileService.validateAccessible` 校验可访问；文件夹拒绝预览）。
2. 按 `versionId` 查 `file_version`；为空或 `file_node_id != nodeId` → `FILE_NOT_FOUND`，文案「版本不存在」。
3. 按节点后缀分派：
   - 图片：生成/复用版本缩略图，key = `thumbnails/{nodeId}/v{versionNum}/{size}.jpg`，返回预签名 URL
   - 视频 / 音频 / PDF：`storageService.generateDownloadUrl(version.storagePath)`（1 小时预签名）
   - 文本类：读取版本对象内容，超过 500KB 截断（与现有逻辑一致）
   - Office 及其它：`PreviewResultVO.unsupported(suffix)`
4. 重构：把现有 `preview(nodeId)` 的后缀分派抽为私有方法 `dispatch(storagePath, suffix, thumbnailKey)`；当前版本缩略图 key 保持 `thumbnails/{nodeId}/{size}.jpg` 不变，版本缩略图走新 key，无需缓存迁移。

## 异常处理

| 场景 | 行为 |
|------|------|
| node 不存在 / 已删除 | `FILE_NOT_FOUND`（沿用） |
| 无访问权限 | 沿用 `validateAccessible` 现有异常 |
| 版本不存在 / 版本不属于该文件 | `FILE_NOT_FOUND`「版本不存在」 |
| 版本对象读取失败 | `STORAGE_SERVICE_ERROR`「读取文件内容失败」 |

## 数据模型

复用 `file_version`：`id` / `file_node_id` / `version_num` / `file_size` / `storage_path` / `file_md5`。无需新增字段。

# 六、数据库设计

无表、字段、索引变更，无迁移脚本，`schema_version` 不新增记录。仅为 `st-preview` 集成测试的 H2 `schema.sql` 补 `file_version` 建表（对齐 `docker/mysql/init` 中该表结构），不涉及真库。

# 七、安全设计

- 越权防护：先校验节点可访问，再校验 `version.file_node_id == nodeId`，不允许仅凭 `versionId` 取对象；两个条件都满足才生成预签名 URL。
- 租户隔离：`FileNodeMapper` / `FileVersionMapper` 走现有 MyBatis-Plus 租户拦截；跨租户 `versionId` 查不到，按「版本不存在」处理。
- 分享链路不在范围：分享链接继续预览当前版本，本次不改 `ShareController`。
- 链接时效：预签名 URL 1 小时有效，与现有预览、下载一致，不产生长期公网链接。
- 读操作不写审计日志，与现有普通预览一致。

# 八、性能设计

- 版本缩略图按 `v{versionNum}` 分目录缓存，重复预览不重复生成（首次生成成本与现有当前版本缩略图一致）。
- 文本预览沿用 500KB 截断，避免大文件全量回传。
- 单次请求 2 条按主键/索引查询（node + version），无列表级 N+1。

# 九、开发计划

```
Task1 后端：PreviewController/PreviewService/PreviewServiceImpl 版本预览 + 缩略图版本化 + 集成测试
验证：mvn -pl st-preview -am test
Task2 前端：VersionHistoryDialog 预览按钮 + PreviewModal 版本模式 + FileBrowserDialogs 接线
验证：cd st-web && npm run build
```

- 接口契约在本文件定版，Task1 / Task2 可并行开发，前端按本契约先接真实接口。
- 编译与测试由主线程串行执行（避免 Maven 本地仓库并发锁）。

# 十、遗留问题点（Grill Me 拷打收敛）

| 编号 | 遗留问题 | 影响 | 建议方案 | 用户裁决 |
|------|---------|------|---------|---------|
| P1 | 支持哪些格式？Office（Word/Excel/PPT）历史版本是否要在线预览 | Office 老版本无法直接看内容，只能先恢复 | 本次仅支持图片/视频/音频/文本/PDF；Office 提示「暂不支持在线预览」 | 初次确认按建议（2026-09-10）；**用户实测后改判：Office 历史版本要能在线预览，改为 OnlyOffice 只读打开（见第十二章）** |
| P2 | 「预览」入口是否也对当前版本显示 | 当前版本会出现与文件预览重复的入口 | 仅非当前版本显示「预览」，当前版本走文件预览 | 已确认：按建议（2026-09-10） |
| P3 | 是否同时支持「下载历史版本」 | 想留存旧版本需先恢复，恢复会改动当前版本 | 本次只做预览，不做下载；下载作为后续独立需求 | 已确认：按建议（2026-09-10） |

> 拷打记录：已收敛目标（只看内容不改版本）、边界（不改当前版本/不分享/不编辑）、异常（版本不属于该文件、跨租户、对象缺失）、影响（无 DB 变更、接口新增不改旧契约）、性能（缩略图按版本缓存）五轮。

# 十一、风险分析

| 风险 | 影响 | 解决方案 |
|------|------|---------|
| 复用 PreviewModal 时误触发 OnlyOffice 跳转 | 历史版本预览跳到编辑器，展示的是当前版本 | 版本模式短路 OnlyOffice 分支，测试用例覆盖 Office 历史版本 |
| 缩略图 key 命名冲突 | 历史版本缩略图覆盖当前版本缩略图 | 版本 key 独立命名空间 `v{versionNum}`，当前版本 key 不变 |
| 预览层被版本弹窗遮挡 | 预览不可见 | 预览层 z-index 高于版本弹窗，手工验证两个弹窗叠加 |
| st-preview 测试 schema 缺 `file_version` | 集成测试无法执行 | 测试 H2 schema 补建表 |
| 版本物理对象已被清理（裁剪逻辑保守保留对象） | 预览报对象不存在 | 读取失败返回 `STORAGE_SERVICE_ERROR` 文案，不返回空预览 |

# 十二、追加设计：Office 历史版本只读预览（2026-09-10 用户改判 P1）

## 背景

用户覆盖上传后预览该历史版本，前端提示「该类型历史版本暂不支持在线预览」（文件为 Office 类型）。
原 P1 把 Office 排除在外，用户实测后要求支持：Office 历史版本走 OnlyOffice 只读打开。

## 方案

沿用现有编辑器链路，只增加"版本"维度，不新增页面：

1. `GET /api/file/{nodeId}/editor/config` 增加**可选** `versionId`（不传 = 现有行为，向后兼容）。
2. 传 `versionId` 时返回版本只读配置：`document.key = {nodeId}_v{versionId}`（与当前版本 key 区分，避免缓存复用）、`permissions.edit=false`、`editorConfig.mode=view`、**不下发 callbackUrl**（无保存通道，杜绝旧内容覆盖当前版本）、不登记编辑锁。
3. `document.url` 仍指向 `/api/file/{nodeId}/stream`，但编辑器令牌额外携带 `versionId` 声明；`/stream` 读取该声明后返回对应版本对象（Range 断点续传、限速逻辑完全复用）。声明由服务端签发，客户端无法伪造版本。
4. 前端：版本预览遇到 Office（docx/xlsx/pptx）跳转 `/file/{id}/editor?mode=view&versionId=xxx`；PDF 继续用内置查看器（预签名 URL）。

## 影响文件

- 后端：`JwtUtils`、`EditorConfigService(+Impl)`、`EditorController`、`FileController`、`DownloadService(+Impl)`
- 前端：`lib/editor.ts`、`pages/EditorPage.tsx`、`components/preview/PreviewModal.tsx`
- 测试：`EditorConfigServiceImplTest` 新增 2 例（只读且无 callbackUrl、版本不属于该节点抛错）

## 风险与限制

| 风险 | 说明 | 处理 |
|------|------|------|
| docservice 是否接受无 callbackUrl 的只读配置 | 只读查看理论上不需要回调；若该版本 docservice 强制要求，表现为编辑器打不开 | 待运行环境实测；如需可改指向专用只读回调端点（不落盘） |
| 版本 fileType 取自当前节点后缀 | 若覆盖上传时改了扩展名，老版本可能渲染异常 | 需按版本存后缀才能彻底解决（涉及 DB 变更），暂不处理 |
| 版本物理对象已清理 | docservice 拉取 404 → 编辑器报错 | 属对象保留策略问题，另行评估 |
