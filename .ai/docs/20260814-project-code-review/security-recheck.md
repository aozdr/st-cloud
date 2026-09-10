# 分享访问链路安全复检报告（M1 改动后）

- Task: `TASK-SEC-SHARE`（reviewer / security）
- Dispatch: `sec-share-001`
- 日期: 2026-08-14
- 方式: 只读静态复检（不修改任何 `st-*` 代码，不创建子 Agent）

## 1. 审查范围

| 维度 | 内容 |
|------|------|
| 后端 | `st-share`（ShareServiceImpl / ShareController / DTO / FileShare / 集成测试） |
| 相关核心 | `st-core`（FileServiceImpl.validateAccessible、DownloadServiceImpl.generateDownloadUrl、FileNodeMapper.countInaccessibleAncestors、UploadServiceImpl 路径构造） |
| 认证/租户 | `st-auth` SecurityConfig / JwtAuthenticationFilter、`st-common` TenantContext / MyBatisPlusConfig |
| 数据库 | `docker/mysql/init/02_create_tables.sql`（file_share 定义） |
| 文档 | `.ai/docs/20260813-share-expiry/{requirement,design,testcases,changereport}.md`、`.ai/docs/20260814-project-code-review/spec.md`、`.ai/knowledge/{data-model,api-reference}.md` |
| 校验依据 | M1 changereport 记录的验证结果（`mvn -pl st-share -am test` 全绿、10 条集成测试、tsc/build 通过）；本报告为静态复检，未重复执行 mvn test |

审查聚焦 5 项：过期校验、`clearExpireAt` 权限、越权与分享码可枚举性、提取码安全、取消/过期/永久状态组合。

## 2. 逐项结论（PASS / BLOCK）

### 2.1 过期校验 — PASS（附 1 条 P3 边界建议）

- `validateShareAccess` 统一执行过期判定并抛 `SHARE_EXPIRED(3002)`，被 `accessShare` / `getDownloadUrl` / `listShareFiles` / `streamShareFile` 四个入口调用（`ShareServiceImpl.java` L299-330），与 requirement 验收 2 一致；集成测试 S5/S13 锁定。
- 创建校验：`createShare` L79-84，`expireAt` 非空时必须 `isAfter(Asia/Shanghai 当前墙钟)`，否则 `BAD_REQUEST`（测试 S3）。
- 更新校验：`updateShare` L143-147，改期同样要求晚于当前时间（测试 S11）；`clearExpireAt=true` 优先清除（L138-142，测试 S10/S14）。
- 时间语义：前后端统一 Asia/Shanghai 本地墙钟，前端已改本地时间格式提交（changereport 确认），无 8 小时偏移。
- 边界建议（P3）：`validateShareAccess` L321 使用 `expireAt.isBefore(now)`，在恰好等于当前时刻（纳秒级）仍可访问一次；建议改为 `!expireAt.isAfter(now)`，与创建/更新的判定方向一致。

### 2.2 clearExpireAt 权限 — PASS

- `updateShare` 先执行 `checkOwnership(shareId)`（L124，实现 L349-357）：非分享创建者抛 `SHARE_ACCESS_DENIED(3004)`；`clearExpireAt` 只有通过该路径才能生效。
- `listShares` 按 `creatorId` 过滤（L97-108），`cancelShare` 同样 `checkOwnership`（L114）。普通用户无法篡改他人分享的过期时间。

### 2.3 越权与分享码可枚举性 — BLOCK（3 项）

- 非所有者更新/取消：PASS（见 2.2）。
- **BLOCK S-01**：`createShare` 缺少文件资源级所有权/成员校验，可分享他人文件（详见问题清单）。
- **BLOCK S-03**：分享子树校验 `path.startsWith(root.path)` 缺少目录边界，存在跨目录/同名前缀文件越权（详见问题清单）。
- **BLOCK S-06**：分享码仅 8 位十六进制（32 bit 熵）+ 公开接口无请求限流，存在枚举风险（详见问题清单）。

### 2.4 提取码 — BLOCK（1 项）

- **BLOCK S-04**：提取码明文存储、无 BCrypt 匹配分支、无错误次数限制与请求限流，存在暴力破解与敏感数据存储风险（详见问题清单）。

### 2.5 状态流转组合 — PASS（附 1 条 P3 数据完整性建议）

- 取消（`status=0`）：`validateShareAccess` L318-319 按 `SHARE_NOT_FOUND` 拒绝，链接立即失效。
- 过期：L321-323 `SHARE_EXPIRED` 判定**优先于提取码校验**（测试 S13），不会被错误码掩盖。
- 永久（`expireAt=null`）：跳过过期判定，直接按状态/提取码放行。
- 建议（P3，S-10）：`updateShare` L153-155 允许任意设置 `status`（可复活已取消分享、可写非法值 2/99 等），且 `shareType` 切到私密时不强制密码非空；建议限定 status ∈ {0,1} 且取消后不可复活，私密类型必须携带非空密码。

## 3. 问题清单

### S-01 [P0/BLOCK] 创建分享缺少文件所有权校验（越权分享他人文件）

- 位置：`st-share/.../service/impl/ShareServiceImpl.java` L63-66（`createShare`）
- 根因：创建前仅调用 `fileService.validateAccessible(fileNodeId)`（L64），而 `st-core/.../FileServiceImpl.java` L469-486 的 `validateAccessible` 仅执行 `fileNodeMapper.countInaccessibleAncestors(nodeId) == 0`（`FileNodeMapper.java` L58/L79-90：只检查祖先链 `status <> 0`，即回收/删除态），**不校验 `owner_id`、`space_id` 归属或团队成员身份**。
- 影响：任意持有 `share:create` 权限的同租户用户，可通过已知/枚举的 `fileNodeId` 创建他人个人文件（`space_id IS NULL`）或团队文件的分享链接，再通过公开访问接口外泄；团队空间场景同样无成员校验。属越权 + 数据泄露。
- 修复建议：
  1. 个人文件（`spaceId == null || spaceId <= 0`）：必须 `node.getOwnerId().equals(UserContext.getUserId())`；
  2. 团队文件：复用 `FileServiceImpl.validateTeamNode(spaceId, nodeId)` 并校验当前用户为该空间成员；
  3. 补充"分享他人文件被拒"集成测试（越权用例）。

### S-02 [P0/BLOCK] 公开分享下载对个人文件 NPE/500，且 stream 可绕过次数与权限控制

- 位置：
  - `st-share/.../ShareServiceImpl.java` L225（`getDownloadUrl` 调用 `downloadService.generateDownloadUrl`）
  - `st-core/.../DownloadServiceImpl.java` L50-60（个人文件 owner 校验 `!UserContext.getUserId().equals(node.getOwnerId())`）
  - `st-auth/.../SecurityConfig.java` L48（`/api/share/access/**` permitAll，匿名可访问）
- 根因：分享下载走 `DownloadServiceImpl.generateDownloadUrl`，其对个人文件（`space_id` 为 NULL，见 `02_create_tables.sql` L71）强制 owner 校验。匿名用户 `UserContext.getUserId()` 为 null（`UserContext.java` L28-31）→ `null.equals(...)` 抛 NPE → 500；已登录非所有者 → `PERMISSION_DENIED`。**核心分享下载功能在匿名场景不可用**（api-reference L149 明确"无需登录认证"）。
- 次生问题：`streamShareFile`（`ShareServiceImpl.java` L255-296）不检查 `downloadLimit` 与 `permission`，直接经 `storageService.downloadObject` 全量回传，实际成为绕过下载次数限制与"仅查看"权限的通道。
- 修复建议：
  1. 分享链路不经过 `DownloadServiceImpl` 的 owner 校验：在 `ShareServiceImpl` 通过 `validateShareAccess` + 子树校验后直接 `storageService.generateDownloadUrl(storagePath)`（抽公共方法或新增 share 专用下载入口）；
  2. `streamShareFile` 补充 `downloadLimit` 与 `permission` 校验（下载次数消耗与 `getDownloadUrl` 统一口径）。

### S-03 [P1/BLOCK] 子树 path 前缀校验缺少目录边界（跨目录/同名前缀越权）

- 位置：`st-share/.../ShareServiceImpl.java` L215（`getDownloadUrl`）、L250（`listShareFiles`）、L281（`streamShareFile`）
- 根因：`targetNode.getPath().startsWith(root.getPath())` 未加 `"/"` 边界。路径格式为 `parentPath + "/" + name`（`UploadServiceImpl.java` L100/L167），因此：
  - 文件夹分享根 `/doc` 可匹配 `/documents/x`；
  - 单文件分享根 `/a.txt` 可匹配 `/a.txt2`。
  - 配合自增 `fileNodeId`（AUTO_INCREMENT）与 `accessShare` 返回的 `fileNodeId`（L192），攻击者可枚举节点 ID 越权访问分享根之外的兄弟子树文件。
- 对照：`FileServiceImpl.java` L188 已正确使用 `node.getPath() + "/"` 边界，分享模块未遵循同一约定。
- 修复建议：统一改为 `path.equals(rootPath) || path.startsWith(rootPath + "/")`（rootPath 非空时）；三处（下载/列表/流式）同步修复并补边界用例（如根 `doc` vs `documents`、`a.txt` vs `a.txt2`）。

### S-04 [P1/BLOCK] 提取码明文存储 + 无 BCrypt 兼容 + 无错误次数限制/请求限流

- 位置：
  - 存储：`ShareServiceImpl.java` L70-76（`createShare` 明文 setPassword）；`02_create_tables.sql` L136 注释却声明 "访问密码(BCrypt)"
  - 校验：`ShareServiceImpl.java` L325（`password.equals(share.getPassword())`）
  - 限流：`SecurityConfig.java` L48 公开接口无任何请求频控
- 根因/影响：
  1. 新密码明文入库，与 schema 注释语义不符，数据库泄露即密码泄露；
  2. 注释提示"旧数据可能为 BCrypt"，但代码无 `BCryptPasswordEncoder.matches` 分支，旧私密分享将永远校验失败（功能回归）；
  3. 公开接口无错误次数限制，4-6 位短提取码可被在线暴力枚举（且 `SHARE_PASSWORD_ERROR(3003)` 明确回显错误）。
- 修复建议：新建密码统一 BCrypt 哈希存储（`PasswordEncoder` 已在 `SecurityConfig` L58 声明）；校验用 `matches` 并对历史明文做兼容迁移；对 `/api/share/access/**` 增加 IP/会话粒度限流与失败计数（如 5 次/15 分钟，Redis 计数）。

### S-05 [P2] permission 字段未在下载/流式路径强制执行

- 位置：`ShareServiceImpl.java` L196-225（`getDownloadUrl`）、L255-296（`streamShareFile`）
- 说明：`file_share.permission` 语义为 0-查看 / 1-下载（`data-model.md` L115），默认 0（`CreateShareRequest` L28），但下载与流式全文回传均不检查该字段。若产品语义为"0=仅预览不可下载"，则属权限绕过（需主线程确认语义）；若语义为"查看即含下载"，则字段冗余且应删除以免误导。
- 修复建议：确认语义后二选一：① 下载/流式强制 `permission >= 1`，仅查看分享提供受限预览；② 删除 permission 字段或明确定义为展示性元数据。

### S-06 [P2] 分享码熵 32 bit + 公开访问无限流（枚举风险）

- 位置：`ShareServiceImpl.java` L360-362（`generateShareCode`：UUID 前 8 位十六进制）；`SecurityConfig.java` L48
- 影响：8 hex = 32 bit 熵，公开接口（access/download/list/stream）无限流；`uk_share_code` 唯一索引（`02_create_tables.sql` L147）冲突时 insert 将抛 DuplicateKeyException（500），生成侧也未做冲突重试。
- 修复建议：`SecureRandom` 生成 8-10 位大写字母数字（约 48+ bit）或 12 hex；生成侧冲突重试；公开访问接口加网关/过滤器级 IP 限流。

### S-07 [P2] download_count 检查-递增竞态（TOCTOU）+ 提前计数

- 位置：`ShareServiceImpl.java` L200-204（检查 `downloadCount >= downloadLimit`）、L226-229（`setSql("download_count = download_count + 1")`）
- 影响：并发下可突破下载次数上限；计数在生成预签名 URL 时发生，实际未下载也消耗次数。
- 修复建议：改为原子条件更新 `UPDATE file_share SET download_count = download_count + 1 WHERE id = ? AND (download_limit IS NULL OR download_count < download_limit)` 并按影响行数判定；或改为下载令牌实际消费时计数。

### S-08 [P2] 匿名访问租户兜底为 1，跨租户分享链接不可达（功能 + 语义）

- 位置：`st-common/.../TenantContext.java` L18-29（SAAS 下未设置租户兜底返回 1）；`MyBatisPlusConfig.java` L40-42（tenant 拦截器注入 `tenant_id`）
- 影响：匿名访问分享时所有查询按 `tenant_id=1` 过滤，非租户 1 创建的分享将返回 `SHARE_NOT_FOUND`，跨租户分享链接失效；同时说明"分享码全局唯一但访问被租户隔离"的语义矛盾。
- 修复建议：分享访问应凭 `share_code` 全局解析（将 `file_share` 加入 tenant 拦截器 ignore 名单并按 code + deleted 查询，或显式跨租户查询后再做过期/密码校验），需主线程确认多租户分享产品语义。

### S-09 [P2] streamShareFile 匿名大文件流式回传，无速率限制/大小上限（带宽 DoS）

- 位置：`ShareServiceImpl.java` L255-296
- 影响：公开接口匿名可反复请求大文件全文流式传输（8KB buffer 直读 S3），无下载限速、无并发/总量限制，可被用于带宽消耗型 DoS。
- 修复建议：流式传输接入 `UserTransferLimiter`/`SpeedLimitService` 限速（参考 `DownloadServiceImpl.pacedTransfer` L152-160）、增加单次/单 IP 并发与累计限制。

### S-10 [P3] updateShare 状态/密码变更缺少完整性校验

- 位置：`ShareServiceImpl.java` L133-155
- 问题：`status` 可任意写入（可复活已取消分享 `0→1`、可写 2/99 等非法值）；`shareType` 切为 1（私密）时不强制 `password` 非空，可产生"永久无法访问"的分享。
- 修复建议：status 仅允许 0/1 且取消后禁止复活；私密类型要求非空密码并走 BCrypt。

### S-11 [P3] 过期等值边界

- 位置：`ShareServiceImpl.java` L321
- 建议：`isBefore(now)` 改为 `!isAfter(now)`，保证"到期即失效"语义与创建/更新校验一致。

### S-12 [P3] 错误码泄露分享存在/过期状态（存在性 oracle）

- 位置：`ShareServiceImpl.java` L318-326
- 说明：`SHARE_NOT_FOUND` / `SHARE_EXPIRED` / `SHARE_PASSWORD_ERROR` 三者可区分"分享不存在 / 已过期 / 密码错误"，未授权者可探测分享码有效性与过期状态；结合 S-06 便于定向攻击。
- 修复建议：权衡产品体验后，对外统一返回通用"分享不可用"（内部保留明细日志）。

### S-13 [P3] 计数与实际行为不一致

- 位置：`ShareServiceImpl.java` L177-181（view_count 在文件存在性校验前递增）、L226-229（download_count 在 URL 生成时递增）
- 建议：调整计数时机或在失败路径回滚，保证统计口径与真实访问/下载一致。

## 4. 结论

**复检结论：不通过（BLOCK）。** M1 迭代的过期时间校验（创建/更新未来时间、`SHARE_EXPIRED` 四入口覆盖、`clearExpireAt` 所有者专属、过期优先于提取码）已正确落地并通过集成测试，相关项 PASS；但分享链路存在 3 项阻断级问题：

1. **S-01（P0）**：`createShare` 无文件所有权/成员校验，可越权分享他人文件；
2. **S-02（P0）**：公开分享下载对个人文件 500/NPE，核心功能不可用，且 `streamShareFile` 绕过次数与权限控制；
3. **S-03（P1）**：子树 path 前缀校验缺边界，可越权访问兄弟同名前缀文件。

另有提取码明文 + 无限流（S-04，P1）、permission 未强制（S-05）、分享码熵不足 + 公开无限流（S-06）等中危项。建议主线程优先修复 P0/P1 后重派安全复检；S-05/S-08 涉及产品语义，需主线程确认后定版。

## 5. 审查局限

- 本次为静态代码复检，未重新执行 `mvn test` / 运行期接口探测；S-01/S-02/S-03 结论基于代码路径与 SQL 语义推导，建议修复后以集成测试/联调复核。
- 未修改任何 `st-*` 代码，仅产出本报告。
