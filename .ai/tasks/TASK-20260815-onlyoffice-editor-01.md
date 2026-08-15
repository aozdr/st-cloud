# TASK-20260815-onlyoffice-editor-01：在线文档编辑实现

## 目标

基于 OnlyOffice 社区版实现 docx/xlsx/pptx 在线编辑：后端 config/回调/版本/保护，
前端编辑器页面，docker-compose 内置容器，无数据库表变更（仅 file_version.source 列）。

## 修改范围

### 后端 st-core

- `entity/FileVersion.java`：新增 `source` 字段（0=上传覆盖 / 1=编辑器保存）
- `common/response/ResultCode.java`：新增 `FILE_EDITING(2010, "文件正在编辑中")`
- 新增 `editor/` 包：
  - `EditorProperties`（stcloud.onlyoffice.*：url / public-base-url / jwt-secret）
  - `EditorPermissionService` + impl（个人 owner / 团队 upload / 分享 upload；格式支持）
  - `EditorConfigService` + impl（生成 OnlyOffice config + JWT）
  - `EditorCallbackService` + impl（验签 / 落盘 / 版本 / 事件 / 配额 / 幂等 / 编辑标记）
  - `EditorLockService`（Redis 编辑标记 Set + 保存锁 SETNX；滑动 TTL）
  - `EditorController`（GET config / POST callback）
  - `dto/`（EditorConfigResponse / OnlyOfficeCallbackRequest）
- `service/impl/VersionServiceImpl.java`：`snapshotCurrentVersion` 支持 source；
  `restoreVersion` 加编辑保护；新增 `pruneEditorVersions(nodeId, 20)`
- `service/impl/FileServiceImpl.java`：rename/move/delete（含团队路径）加编辑保护
- `service/impl/UploadServiceImpl.java`：覆盖上传（replaceFileId）加编辑保护
- `common/utils/JwtUtils.java`：新增 `generateEditorToken`（type=editor，绑定 nodeId，5min，不单次消费）
- `st-auth/security/JwtAuthenticationFilter.java`：editor 类型放行 stream（端点收敛+nodeId 绑定，跳过单次消费）

### 前端 st-web

- 新增 `pages/EditorPage.tsx`（全屏 iframe + 错误回退）
- `App.tsx` 新增路由 `/file/:nodeId/editor`
- `components/file/FileToolbar.tsx` + `ContextMenu.tsx`：「在线编辑」入口（docx/xlsx/pptx + 有权限）
- `lib/` API 封装 + `types` 类型

### 配置与部署

- `docker/docker-compose.yml`：onlyoffice 服务（image onlyoffice/documentserver，8081:80，
  JWT_ENABLED/JWT_SECRET 环境变量，extra_hosts host.docker.internal:host-gateway）
- `st-api/src/main/resources/application.yml`：`stcloud.onlyoffice.*`
- `docker/mysql/init/36_editor_version_source.sql`：file_version.source 列（幂等）
- `st-core/src/test/resources/schema.sql`：file_version 加 source 列

## 禁止修改范围

- team_role / team_folder_permission / file_share 权限模型结构
- 分享/团队/同步/搜索模块业务逻辑（仅消费其事件/权限接口）
- 既有上传/下载主流程语义（覆盖上传仅加保护拦截）

## 验收标准

- 编译通过：`mvn -pl st-core -am compile`、`mvn -pl st-api -am compile`
- 测试通过：`mvn test`（含 SchemaConsistencyTest）
- 前端 `npm run build` / `tsc --noEmit` 通过
- TC-01~TC-28 覆盖实现（核心：权限判定、回调验签、版本 source、保护拦截、幂等）

## 验证命令

```bash
mvn -pl st-core -am test -Dsurefire.failIfNoSpecifiedTests=false
mvn test
cd st-web && npx tsc --noEmit && npm run build
```

## 输出要求

- 产出 `.ai/docs/20260815-onlyoffice-editor/changereport.md`
- 核心逻辑（权限判定/回调验签/版本/锁）加中文注释
