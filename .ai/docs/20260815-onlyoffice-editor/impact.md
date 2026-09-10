# 影响分析：在线文档编辑（OnlyOffice 社区版）

> 归属：IMPACT_ANALYSIS（大型任务第二环，依赖 REQ_ANALYSIS done）

## 需求摘要

基于 OnlyOffice 社区版（docker-compose 内置）为星云盘新增 docx/xlsx/pptx 在线编辑：
复用现有权限/版本/事件体系，用户已确认保存版本策略（关闭时生成一版，上限 20）、
协同不互斥（保存串行化）、仅内置容器部署。

## 影响范围

### 后端（st-core）

| 文件/模块 | 变更 | 风险 |
|----------|------|------|
| 新增 `editor/EditorController` | `GET /api/file/{nodeId}/editor/config`、`POST /api/file/{nodeId}/editor/callback` | 中（回调越权） |
| 新增 `editor/EditorConfigService` | 构建 OnlyOffice config、JWT 签名、文档 URL | 中 |
| 新增 `editor/EditorCallbackService` | 回调校验、落盘、版本生成、事件触发 | 高（文件写入） |
| 新增 `editor/EditorPermissionService` | 统一编辑权限判定（个人/团队/分享） | 中 |
| `FileService`（delete/move/rename） | 增加编辑标记拦截 | 低 |
| `VersionService` | 版本上限 20 裁剪（按 P1/D1 裁决）；`file_version` 新增 `source` 列区分来源 | 中（物理对象删除） |
| 新增 Redis 编辑标记 + 保存串行锁 | `editor:active:{id}` / `editor:save-lock:{id}` | 低 |

### 前端（st-web）

| 文件/模块 | 变更 |
|----------|------|
| 新增 `pages/EditorPage.tsx` | iframe 加载 OnlyOffice，路由 `/file/:nodeId/editor` |
| `App.tsx` | 新增路由 |
| `components/file/FileToolbar.tsx` / `ContextMenu.tsx` | 「在线编辑」入口 |
| `lib/api.ts` / `types` | 编辑器配置接口与类型 |

### 配置与部署

| 文件 | 变更 |
|------|------|
| `docker/docker-compose.yml` | 新增 `onlyoffice` 服务（端口 8081:80，JWT 密钥环境变量） |
| `st-api/application.yml` | 新增 `stcloud.onlyoffice.*`（url、jwt-secret、public-base-url） |
| 环境变量 | `STCLOUD_ONLYOFFICE_SECRET` |

### 数据库变更（唯一一项）

- `file_version` 新增 `source` 列（TINYINT，0=上传覆盖 / 1=编辑器保存），36 号迁移脚本
- 同步 H2 `schema.sql`；`SchemaConsistencyTest` 校验；MySQL 执行留部署环境（测试环境 H2 先行）

### 无变更

- 权限模型（team_role / team_folder_permission / file_share 权限点不扩展）
- 分享/团队/同步/搜索模块业务逻辑（仅消费其事件与权限接口）

## 影响清单（文档/测试/知识库）

- 测试：新增编辑器配置/回调/权限/并发集成测试；现有 mvn test 全量回归
- 文档：architecture-review / design / testcases / codereview / security / testreport / changereport
- 知识库：architecture / api-reference / frontend / business-domain / project-overview（docker 服务清单）

## 风险等级

**中高**（涉及文件写入与外部回调）：

- 回调伪造导致越权覆盖 → 必须 JWT 签名校验 + 服务端权限复核 + 审计
- OnlyOffice 与后端互相访问的可达性（容器回调宿主）→ 需 `public-base-url` 与 `host.docker.internal` 配置说明
- 版本裁剪误删被引用物理对象 → 复用 ref_count 引用归零逻辑，避免硬删

## 建议

- 后端改动集中在 st-core 新增 editor 包，不动既有上传/版本主链路（新增拦截点最小侵入）
- 保存落盘复用 FileObject/版本既有逻辑，保证去重与配额一致
- 上线顺序：docker-compose 起 OnlyOffice → 后端端点 → 前端页面 → 联调 → 回归
