# 团队搜索与显式授权变更报告

## 背景

本次实现对应 `tsw-design-r1` 的团队全文搜索和共用显式主体授权部分。个人 `/api/search` 保持原分页和响应契约，团队链路单独使用游标和即时权限复核。

## 实际改动

- 新增 `st-common` 的 `TeamFileAccessPolicy` 标量接口及 `st-team` 的 `TeamFileAccessPolicyImpl`。策略按显式 tenant/user/space/node 校验正常空间、成员有效期、节点与祖先状态、隐藏、上传完成状态、空间/租户归属及角色/文件夹权限；管理员也必须先通过成员关系。
- `FolderPermissionService` 保留原 `resolvePermissions` 的缓存和 mapper 调用行为，新增不读共享缓存的 tenant-aware fresh 解析。fresh 遇到跨租户、断链、循环或超过 20 层父链时返回空权限。
- `st-search` 新增 `/api/search/team`、`TeamSearchService`、`TeamSearchResultPage`。ES 查询固定 tenant/space 过滤和稳定 `fileId` 排序，folder scope 先用已核验路径做 exact/prefix 初筛，再以数据库父链复核；候选最多批量 100 条、总扫描预算 2000 条。
- 团队候选只有在当前数据库节点正常、元数据名称/路径与索引一致、文件 MD5 一致、显式主体可见后才构造 VO/高亮；返回只含 `records/hasMore/nextCursor`，不返回 ES total。最终 `size+1` 结果再次核权，失权 lookahead 会继续扫描或安全收窄。
- 游标为 Base64URL payload + HMAC-SHA256，绑定版本、主体、租户、空间、目录、全部查询参数 hash、最后返回 ID 和过期时间；密钥只从 `stcloud.search.team-cursor-secret` 或 `STCLOUD_SEARCH_TEAM_CURSOR_SECRET` 注入，缺失时团队请求返回 `SEARCH_UNAVAILABLE`。
- `SearchIndexInitializer` 与索引写入/元数据更新增补 `tenantId`、`spaceId`、`parentId`、`fileMd5` 字段；团队 VO 增补 `spaceId`、`parentId`。
- 新增 `TeamFileAccessPolicyTest`、`FolderPermissionFreshTest`、`TeamSearchServiceTest`，覆盖成员/租户/祖先授权、fresh 脏链、MD5 复核、无权候选、lookahead、游标篡改及 ES 故障等边界。

## 验证证据

- 已执行 `git diff --check`，无 whitespace 错误。
- 按 TASK 约束，编码阶段未运行 Maven、共享缓存构建、数据库迁移或 ES 实例验证；上述测试代码待主线程串行执行。

## 风险与依赖

- 必须在部署环境配置 `STCLOUD_SEARCH_TEAM_CURSOR_SECRET`（建议至少 32 字节）；未配置不会允许团队搜索请求继续，以避免分页链路使用无签名游标。
- 聚合应用需确认该属性进入 Spring Environment；实现同时直接读取环境变量，避免依赖 st-search 作为库时的同名 `application.yml` 加载顺序。
- 旧 ES 文档缺少身份/空间/MD5 字段时会被团队查询安全排除，需通过既有受控 reindex 补齐；未在本阶段实测 ES。

## 偏离

无已知 API、权限语义或范围偏离。结果不包含数据库、State、前端或关注/通知实现。
