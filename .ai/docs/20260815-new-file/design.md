# 程序设计文档：新建文件

> 前置：需求已确认（P1 服务端内置模板 / P2 触发同步 / P3 新建后自动打开编辑）。
> 本文档经 Grill Me 拷打收敛（遗留问题点 ≤3 见「十」），并经用户确认后才可进入实现。
> **写作要求：简洁。直说方案与决策。**

# 一、需求分析

## 功能名称

新建文件（txt/docx/xlsx/pptx）

## 功能描述

```
用户：文件列表点「新建」→ 选择类型
操作：服务端生成空白文件并落盘
系统行为：创建 file_node（已完成）→ 返回节点 → 列表刷新；Office 文件自动进入 OnlyOffice 编辑
最终结果：新文件可预览/编辑/分享/同步
```

# 二、系统影响分析

| 类型 | 模块 | 是否修改 |
|------|------|---------|
| 后端 | st-core（新建服务/接口） | 是（新增） |
| 后端 | st-team（团队新建入口） | 是（新增端点） |
| 前端 | st-web（新建菜单/交互） | 是（新增） |
| 数据库 | 无 | 否 |

# 三、整体设计

```
前端「新建」菜单
  → POST /api/file/new（个人）或 /api/team/{spaceId}/files/new（团队）
  → NewFileService.createBlankFile(type, parentId, spaceId, userId)
      ├─ 内置模板（classpath 静态资源）→ 字节流
      ├─ 落 S3（复用 FileObject acquire）
      ├─ 创建 file_node（uploadStatus=COMPLETED）
      ├─ 配额差值 + 云盘容量校验
      └─ 发布 FileIndexEvent(INDEX) + SyncChangeEvent(CREATE)（P2）
  → 返回 FileNodeVO
```

# 四、前端设计

- 入口：FileToolbar「新建」下拉菜单（文件夹 / 文本文档 / Word 文档 / Excel 表格 / PPT 演示）；
  文件列表空白区域右键菜单同款
- 权限：仅个人目录/团队 upload 目录显示；无权限隐藏（D2）
- 交互：选择类型 → 调接口 → 刷新列表；
  docx/xlsx/pptx 成功后跳转 `/file/:nodeId/editor`（带来源路径，P3）；
  txt 留在列表
- 错误：配额不足/无权限 → toast 提示，不跳转

# 五、后端设计

## API

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/file/new` | 个人目录新建（parentId, type） | 登录 + 个人 owner |
| POST | `/api/team/{spaceId}/files/new` | 团队目录新建（parentId, type） | 成员 + upload 权限 |

## NewFileService（st-core）

- `createBlankFile(NewFileRequest, spaceId)`：
  1. 类型映射：txt→空 UTF-8；docx/xlsx/pptx→classpath `templates/blank.{type}` 模板
  2. 命名：默认「新建文本文档.txt / 新建文档.docx / 新建表格.xlsx / 新建演示.pptx」，
     复用 `FileService.resolveNameConflict` 处理重名（D1）
  3. 落 S3：`storageService.uploadObject` + `fileObjectService.acquire`（同 md5 去重）
  4. 创建 file_node：status=NORMAL、uploadStatus=COMPLETED、version 初值、owner/space 归属
  5. 配额：userQuotaMapper/teamStorageMapper 按字节计（模板约 3-5KB），cloudStorageService.checkCapacity
  6. 事件：`reliableEventPublisher.publishFileIndex(INDEX)` + `publishSyncChange(CREATE)`（P2）
- 权限：个人端点校验 owner；团队端点由 TeamController 判定 upload 后调用

## 模板资源

- 位置：`st-core/src/main/resources/templates/blank.docx / blank.xlsx / blank.pptx`
- 生成：标准 OOXML 空白文档（docx：含空段落的 document.xml；xlsx：单 Sheet 空表；pptx：空白一页）
- 校验：OnlyOffice 实测可打开（集成测试验证）

# 六、数据库设计

- 无表结构变更。复用 file_node / file_object / 配额表。

# 七、安全设计

- 权限：个人 owner / 团队 upload 复用现有判定链，防越权新建
- 配额：复用差值校验，防绕过配额
- 类型白名单：type 仅允许 txt/docx/xlsx/pptx，防任意后缀

# 八、性能设计

- 模板为静态资源（classpath），接口无外部 IO；落 S3 一次小对象
- 响应目标 < 500ms

# 九、开发计划

```
Task1: 后端 NewFileService + 模板资源 + 个人/团队接口 + 集成测试
Task2: 前端新建菜单（工具栏+右键）+ 跳转编辑 + 错误提示
Task3: 联调验证（新建各类型/重名/权限/编辑/同步）
```

# 十、遗留问题点（Grill Me 拷打收敛）

| 编号 | 遗留问题 | 影响 | 建议方案 | 用户裁决 |
|------|---------|------|---------|---------|
| D1 | 默认文件名规则 | 用户可见命名习惯 | 「新建文档.docx / 新建表格.xlsx / 新建演示.pptx / 新建文本文档.txt」+ 重名序号 (1)(2) | ✅ 按建议（20260815 用户确认） |
| D2 | 「新建」菜单形态 | 交互整合度 | 工具栏下拉与右键菜单都整合「新建文件夹」+ 4 种文件类型（一个菜单） | ✅ 按建议（20260815 用户确认） |
| D3 | 团队新建入口权限校验粒度 | 是否允许文件夹级权限 | 复用现有 upload 权限点判定（文件夹权限含 upload 即可新建） | ✅ 按建议（20260815 用户确认） |

> 问题点 > 3 时不得定版，继续拷打收敛后再产出。

# 十一、风险分析

| 风险 | 影响 | 解决方案 |
|------|------|---------|
| 模板与 OnlyOffice 不兼容 | 新建文件打不开 | 标准 OOXML + 实测 |
| 新建触发同步造成桌面端文件出现 | 用户预期内（P2 已确认） | 无 |
