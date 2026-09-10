# 程序设计：文件跨空间操作与元数据增强

> 迭代：20260829-file-ops
> 依赖：requirement.md（待确认后定稿）
> 状态：待用户确认，未确认不得进入 TESTCASES / IMPLEMENTED

## 1. 架构决策

| # | 决策 | 理由 |
|---|------|------|
| D1 | 跨空间移动/复制底层原语放 `st-core`（`FileTransferService`），编排与权限校验放 `st-api` 的 `CrossSpaceTransferController` | `st-core` 不依赖 `st-team`，团队空间权限校验必须由能触达 st-team（`TeamService`/`FolderPermissionService`）的聚合层执行 |
| D2 | 转存复用跨空间复制内核，`st-share` 提供分享节点解析，`st-api` 编排权限 | 转存本质 = 分享源 → 目标空间的跨空间复制，仅多一步「校验分享读取权限」 |
| D3 | 元数据单独建 `file_meta` + `file_tag` + `file_node_tag` 表，不塞入 `file_node` | `file_node` 是高热表，标签/描述属低频弱字段；独立表便于扩展且不污染核心查询 |
| D4 | 标签采用多对多，而非 `file_meta.tag_ids` JSON | 支持按标签检索（侧边栏筛选），JSON 列无法高效反查 |
| D5 | 排序增强仅做前端（类型列 + 拖拽本地持久化），不改后端 | 现状 `sortBy/sortDir/foldersFirst` 已是前端可配；多列排序与手动排序不需后端支撑 |
| D6 | 跨空间操作一次请求原子提交，不拆多次调用 | 复制+删除可能跨多次写入，必须保证一致性，避免半成品 |

## 2. 数据模型（MySQL + 同步 H2 schema）

新增 `docker/mysql/init/40_file_meta.sql`（编号接续现有 39 号之后）：

```sql
SET NAMES utf8mb4;
-- 文件元数据（标题/描述/封面）
CREATE TABLE IF NOT EXISTS file_meta (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id    BIGINT       NOT NULL,
  node_id      BIGINT       NOT NULL,
  title        VARCHAR(255) NULL,
  description  VARCHAR(2000) NULL,
  cover_path   VARCHAR(500) NULL,
  created_at   DATETIME     NULL,
  updated_at   DATETIME     NULL,
  deleted      TINYINT      NOT NULL DEFAULT 0,
  UNIQUE KEY uk_node (node_id),
  KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 标签
CREATE TABLE IF NOT EXISTS file_tag (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id    BIGINT       NOT NULL,
  space_id     BIGINT       NULL,          -- NULL=个人标签；>0=团队/空间标签
  name         VARCHAR(64)  NOT NULL,
  color        VARCHAR(16)  NULL,
  created_at   DATETIME     NULL,
  UNIQUE KEY uk_space_name (space_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 节点-标签关联
CREATE TABLE IF NOT EXISTS file_node_tag (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id    BIGINT       NOT NULL,
  node_id      BIGINT       NOT NULL,
  tag_id       BIGINT       NOT NULL,
  created_at   DATETIME     NULL,
  UNIQUE KEY uk_node_tag (node_id, tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

同步修改 `st-core/src/test/resources/schema.sql` 追加同名建表。

## 3. API 契约

### 3.1 转存

`POST /api/share/access/save/{shareCode}`（需登录）

请求：
```json
{
  "targetSpaceId": null,          // null=个人空间；>0=团队空间
  "targetParentId": 0,            // 0=目标空间根目录
  "password": "可选提取码",
  "sourceParentId": null          // 可选：分享内容内子目录（默认根）
}
```

响应：`Result<List<FileNodeVO>>`（转存后新增节点，供前端选中定位）。

校验顺序：提取码/有效期/状态 → 分享权限含 `view` → 目标空间写权限 → 目标空间三层配额 → 执行递归复制。

### 3.2 元数据

- `GET /api/file/{nodeId}/meta` → `FileMetaVO`（title/description/coverPath/tags）
- `PUT /api/file/{nodeId}/meta` → 更新 title/description/coverPath
- `GET /api/file/{nodeId}/tags` → `List<TagVO>`
- `POST /api/file/{nodeId}/tags` → 追加标签（body: `{ "name": "...", "color": "#...", "tagId": 可选 }"`
- `DELETE /api/file/{nodeId}/tags/{tagId}` → 移除标签
- 团队空间对应 `/api/team/{spaceId}/files/{nodeId}/meta` 同构接口

权限：个人空间本人可写；团队空间需对应文件夹权限点（view 可读，upload/manage 可写元数据）。

### 3.3 跨空间移动/复制

`POST /api/file/transfer`（需登录，端点在 st-api 聚合层）

请求：
```json
{
  "op": "move" | "copy",
  "nodeIds": [1, 2, 3],
  "srcSpaceId": null,             // 源空间，null=个人
  "targetSpaceId": null,          // 目标空间，null=个人
  "targetParentId": 0
}
```

响应：`Result<Void>`。前端成功后在目标空间刷新列表。

## 4. 权限与配额

| 操作 | 源权限 | 目标权限 | 配额 |
|------|--------|----------|------|
| 跨空间复制 | 源只读（个人本人/团队对应权限点） | 目标目录写 | 目标三层扣减 |
| 跨空间移动 | 源只读 | 目标目录写 | 目标扣减 + 源释放 |
| 转存 | 分享 `view` | 目标目录写 | 目标三层扣减 |
| 元数据编辑 | 源可读 | 目标可写（团队） | 不涉及配额 |

- 三层配额复用现有 `QuotaService`（个人 / 团队 / 云盘总容量），按增量记账。
- 团队空间写权限复用 `FolderPermissionService`（含自定义角色与文件夹权限覆盖）。
- 源权限校验复用 `validateAccessible` + 团队 `validateTeamNode` + 权限点计算。

## 5. 事务与一致性

- `transfer` 与 `saveShare` 全程 `@Transactional`；跨空间操作在单事务内完成「写入目标 + 更新源」。
- 对象去重：跨空间移动不改变 `file_object.ref_count`（对象仍被引用）；跨空间复制目标节点 `acquire` 同一对象 `ref_count+1`。
- 跨空间移动 = 复制到目标（ref+1）+ 删除源（ref-1 且仅在源为最后引用时触发对象删除判断），净引用平衡。
- 事件发布：移动/复制/转存均发布 `SyncChangeEvent`（MOVE/CREATE），供同步与索引消费；转存目标若同步根覆盖则自动同步到客户端。
- 失败回滚：任一校验或写入失败抛出业务异常，整体回滚，不产生半成品。

> 约束：跨空间操作不得在事务内执行 S3 网络调用（沿用「S3 先做、DB 后落」原则）；转存/复制的 S3 对象引用为引用计数，不产生新的物理上传。

## 6. 前端改动（st-web）

1. **分享页**：明细/右键菜单增加「保存到我的网盘」；弹窗选择目标空间 + 目录树（复用移动对话框）。
2. **文件详情面板**：新增「元数据」区，支持编辑标题/描述/标签/封面；标签输入可新建。
3. **右键/批量菜单**：移动到/复制到增加「选择空间」能力（个人空间 + 用户加入的团队空间）。
4. **FileTableView**：表头支持类型列排序；排序持久化到 `localStorage` 与 URL query。
5. **FileGrid/FileTable**：可选拖拽排序（本地持久化 `fileManualOrder`）。
6. **API 层**：新增 `saveShare`、`transfer`、`meta`、`tags` 调用封装；`types` 增加 `FileMetaVO`/`TagVO`。

## 7. 测试要点

| 模块 | 用例 |
|------|------|
| 转存 | 私密提取码错误 / 过期 / 已取消→拒绝；权限点无 view→拒绝；同名→加序号；文件夹递归 |
| 跨空间 | 个人→团队、团队→个人、团队→团队；子目录冲突；自身/子目录；配额不足；编辑中移动拦截；失败回滚 |
| 元数据 | 个人/团队读写；标签重复挂载幂等；删除文件后元数据隐藏、恢复可见；永久删除清理 |
| 排序 | 类型列升降序；文件夹优先开关；排序偏好持久化与 URL 同步 |

## 8. 迁移方案

1. 新增 `docker/mysql/init/40_file_meta.sql`（建 3 张表）。
2. 同步 `st-core/src/test/resources/schema.sql`。
3. 运行 H2 测试（含 `SchemaConsistencyTest`）全绿。
4. 运行 `.ai/scripts/compare-schema.ps1` 对比 MySQL，确认无列差异。
5. 向运行中 MySQL 执行 37 号脚本。
6. 更新 `schema_version` 表记录本次迭代。
7. 再次 `compare-schema.ps1` 确认 PASS。

## 9. 风险

- 跨空间移动需原子处理多个源/目标写入，事务范围大；若节点数大可能锁膨胀 → 单次操作限制节点数（建议 ≤ 500），超出分批提示。
- 转存大文件夹需递归复制，服务端 CPU 占用高 → 采用与现有复制相同策略，前端展示 loading，超时提示。
- 标签/元数据为新增表，不迁移历史数据 → 旧节点无元数据时前端隐藏编辑入口，不影响既有功能。
