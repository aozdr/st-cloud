# 程序设计文档：在线文档编辑（OnlyOffice 社区版）

> 前置：架构设计评审通过（architecture-review.md）。
> 本文档经 Grill Me 拷打收敛（遗留问题点 ≤3 见「十」），并经用户确认后才可进入测试用例/实现。
> **写作要求：简洁。直说方案与决策。**

# 一、需求分析

## 功能名称

在线文档编辑（OnlyOffice 社区版，docker-compose 内置）

## 功能描述

```
用户：在文件列表选择 docx/xlsx/pptx，点击「在线编辑」
操作：进入编辑器页面，编辑并保存，关闭后返回列表
系统行为：权限判定 → 生成 config → iframe 打开 OnlyOffice → 回调落盘 → 版本/索引/同步更新
最终结果：文件内容更新，生成新版本，可回滚
```

# 二、系统影响分析

| 类型 | 模块 | 是否修改 |
|------|------|---------|
| 前端 | st-web（EditorPage、路由、入口、API） | 是（新增） |
| 后端 | st-core（editor 包、FileService 拦截） | 是（新增+小改） |
| 数据库 | 无 | 否 |
| 配置 | st-api application.yml | 是 |
| 部署 | docker-compose.yml | 是 |

影响文件预测：

- 新增：`EditorController` / `EditorConfigService` / `EditorCallbackService` / `EditorPermissionService` / `pages/EditorPage.tsx`
- 修改：`FileService`（delete/move/rename 拦截）、`VersionService`（上限裁剪）、`App.tsx`、`FileToolbar/ContextMenu`、`application.yml`、`docker-compose.yml`
- 删除：无

# 三、整体设计方案

```
st-web EditorPage ──iframe──▶ OnlyOffice Document Server (:8081)
      │                                │
      │ GET /editor/config             │ POST /editor/callback
      ▼                                ▼
  st-api（EditorConfigService / EditorCallbackService / Redis）
      │ 落盘 S3（FileObject 去重）
      ▼
  file_node + file_version + 事件（FileIndex / SyncChange）+ 配额
```

# 四、前端设计

- 页面：`EditorPage.tsx`，路由 `/file/:nodeId/editor`（受保护路由，嵌套 AppLayout 之外全屏）
- 流程：进入 → 调 `GET /api/file/{nodeId}/editor/config` → 加载 `api.js` → `new DocsAPI.DocEditor`
- 状态：加载骨架；失败弹窗（「编辑服务暂不可用」+ 「以预览打开」回退）；保存由 OnlyOffice 自带指示
- 入口：文件列表右键菜单 + 工具栏「在线编辑」（仅 docx/xlsx/pptx 且有编辑权限时显示）；
  双击保持现有预览（体验评审 E3 决策）
- 返回：关闭编辑器返回列表时刷新当前目录元数据
- 移动端：iframe 可用；窄屏仅保证可用（体验评审 E2）

# 五、后端设计

## API

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/file/{nodeId}/editor/config` | 返回 `{editorUrl, config}`（config 已带 JWT token） | 登录 + 编辑/只读权限 |
| POST | `/api/file/{nodeId}/editor/callback` | OnlyOffice 保存/关闭回调 | OnlyOffice JWT 验签 + 归属复核 |

## EditorConfig 生成（EditorConfigService）

- 权限判定：`EditorPermissionService.canEdit(nodeId, userId)`
  - 个人文件：owner 可编辑
  - 团队文件：FolderPermissionService 权限集含 `upload`
  - 分享文件：FileShare.permissions 含 `upload`
- `document.url`：`{publicBaseUrl}/api/file/{nodeId}/stream?token={短期下载token}`
- `document.key`：`{nodeId}:{version}`
- `permissions`：edit=可编辑；只读时 `{edit:false, download:按权限, print:按权限}`
- `callbackUrl`：`{publicBaseUrl}/api/file/{nodeId}/editor/callback`
- `token`：JWT（HS256，secret=STCLOUD_ONLYOFFICE_SECRET，claims 含文件归属/用户/过期）

## 保存回调（EditorCallbackService）

```
验签 → 解析 status
  status=2（已保存）：锁 → 下载 url → 校验大小/类型 → 落盘覆盖（不生成版本）
  status=6/7（关闭/强制保存）：同上 + 生成 file_version（上限裁剪）
  → 释放锁 → 更新 file_node size/md5/version → 配额差值 → 发布事件
```

- 幂等：Redis `editor:save-dedup:{key}`（短 TTL）防重复回调
- 失败：返回 500 让 OnlyOffice 按自身策略重试；连续失败记审计
- 编辑标记：`editor:active:{nodeId}` Redis Set（userId）；关闭回调移除当前用户
- 保存锁：`editor:save-lock:{nodeId}` SETNX + 10s TTL，串行化同文件并发保存

## FileService 保护拦截

delete / move / rename（含团队文件操作、版本恢复、覆盖上传）前检查
`editor:active:{nodeId}` 非空 → `BusinessException(FILE_EDITING)`。

## 配置（application.yml）

```yaml
stcloud:
  onlyoffice:
    url: http://localhost:8081          # 前端 iframe 地址
    public-base-url: http://host.docker.internal:8080  # 生产改为可达地址
    jwt-secret: ${STCLOUD_ONLYOFFICE_SECRET:}
```

# 六、数据库设计

- **无表结构变更**。仅版本裁剪逻辑使用既有 `file_version`。
- 版本上限裁剪：按 `file_node_id` 统计版本数，超出上限删除最旧版本记录；
  物理对象删除复用 `ref_count` 引用归零（被其他节点/版本引用时不删）。

# 七、安全设计

- 回调 JWT 验签 + 文件归属/权限复核 + 审计（`@Auditable`）
- document.url 短期 token（TTL 5 分钟），不暴露长期直链
- 下载内容大小/类型校验（防回调投毒超大文件/非文档类型）
- OnlyOffice 容器仅暴露 8081；密钥经环境变量注入，不入源码
- 越权矩阵：无 upload 权限者无法获取可编辑 config；伪造回调无法绕过验签

# 八、性能设计

- 编辑器直连 OnlyOffice，后端无代理压力
- 回调下载仅在保存时发生，单文件串行；版本裁剪一次批量删一条
- Redis 标记/锁 TTL 短，无长驻资源

# 九、开发计划

```
Task1: docker-compose 内置 onlyoffice 容器 + application.yml 配置 + extra_hosts
Task2: 后端 EditorPermissionService + EditorConfigService + config 端点
Task3: 后端 EditorCallbackService（验签/落盘/版本/事件/配额）+ FileService 拦截 + 版本裁剪
Task4: 前端 EditorPage + 路由 + 文件列表入口 + 返回刷新
Task5: 集成联调（编辑/保存/协同/保护/越权） + 测试用例执行
```

# 十、遗留问题点（Grill Me 拷打收敛）

| 编号 | 遗留问题 | 影响 | 建议方案 | 用户裁决 |
|------|---------|------|---------|---------|
| D1 | 版本上限 20 的范围：只对编辑器保存产生的版本生效，还是对全部 file_version（含上传覆盖产生）生效 | 既有上传覆盖行为可能被改变 | 仅编辑器产生的版本参与 20 条裁剪；上传覆盖产生的版本不受影响（file_version 增加 source 列区分来源） | ✅ 按建议（20260815 用户确认） |
| D2 | 只读预览是否统一迁移：pdf/csv/txt/docx 只读都走 OnlyOffice，还是保持现有预览组件（docx-preview/Plyr） | 改动面与预览体验一致性 | 本迭代只把「在线编辑」接入 OnlyOffice；只读预览保持现状，后续迭代再统一 | ✅ 按建议（20260815 用户确认） |
| D3 | 编辑期间对同文件的覆盖上传/版本恢复是否拦截 | 并发写导致内容覆盖丢失 | 编辑标记同时拦截覆盖上传与版本恢复（与删除/移动/重命名同级保护） | ✅ 按建议（20260815 用户确认） |

> 问题点 > 3 时不得定版，继续拷打收敛后再产出。

# 十一、风险分析

| 风险 | 影响 | 解决方案 |
|------|------|---------|
| 生产 public-base-url 未配置导致编辑器空白 | 功能不可用 | 启动检查/文档醒目说明 |
| 回调重复落盘 | 版本/内容重复 | 幂等键 + 保存锁 |
| 版本裁剪误删引用对象 | 数据丢失 | ref_count 归零检查 |
