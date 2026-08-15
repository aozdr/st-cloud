# TASK-20260815-new-file-01：新建文件（后端）

## 目标

实现新建空白文件后端：NewFileService（模板生成/落盘/命名/配额/事件）+ 个人/团队接口。

## 修改范围

### 后端 st-core

- 新增 `NewFileService` + impl（`createBlankFile(type, parentId, spaceId)`）：
  - 类型白名单 txt/docx/xlsx/pptx；默认命名「新建文本文档.txt/新建文档.docx/新建表格.xlsx/新建演示.pptx」
  - 重名复用 `FileService.resolveNameConflict`
  - 模板：txt 空 UTF-8；docx/xlsx/pptx 读 classpath `templates/blank.{type}`
  - 落 S3（storageService.uploadObject + fileObjectService.acquire 去重）
  - 创建 file_node（status=NORMAL、uploadStatus=COMPLETED、version=1、owner/space 归属）
  - 配额差值 + cloudStorageService.checkCapacity
  - 发布 FileIndexEvent(INDEX) + SyncChangeEvent(CREATE)（P2）
- 新增 `NewFileController`：`POST /api/file/new`（个人，owner 校验）
- 新增 DTO：`NewFileRequest{parentId, type}`
- 资源：`src/main/resources/templates/blank.docx / blank.xlsx / blank.pptx`（标准 OOXML 空白模板）

### 后端 st-team

- `TeamController` 新增 `POST /api/team/{spaceId}/files/new`：复用 upload 权限判定后调 NewFileService（D3）

## 禁止修改范围

- 权限模型/数据库结构/上传主流程
- st-web/**

## 验收标准

- TC-01~TC-10 覆盖（新建各类型/重名/权限/配额/事件/类型白名单/状态）
- `mvn -pl st-core -am compile`、`mvn -pl st-api -am compile` 通过
- 新建集成测试（NewFileServiceIntegrationTest）覆盖 TC-01~TC-10 核心

## 验证命令

```bash
mvn -pl st-core -am compile
mvn test（主线程统一执行）
```

## 输出要求

- 核心逻辑（权限/命名/配额/事件）中文注释
- 返回 State Delta（改动文件、验收对照、风险）
