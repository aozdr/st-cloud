# st-core 安全与一致性整改测试用例

## 1. 测试范围与口径

本组用例依据已确认的 `requirement.md`、`design.md`、`impact.md` 和 Review 结果编写，覆盖 P0-01～P0-05、P1-01～P1-08、数据库迁移及 Goal completionCriteria。除标注“当前基线”外，均为实现后必须执行的回归或新增用例。

### 1.1 测试身份与数据夹具

| 标识 | 数据 |
|---|---|
| U-A | tenant=100，user=1001，个人节点 owner=1001 |
| U-B | tenant=100，user=1002，个人节点 owner=1002 |
| U-C | tenant=200，user=2001，跨租户身份 |
| S-1 | tenant=100，spaceId=9001，U-A 为成员，具有 view/upload/download/rename/move/delete |
| S-2 | tenant=100，spaceId=9002，U-A 无成员权限 |
| P-A | U-A 根目录及 `A/child.txt`、`A/sub/grand.txt` |
| P-B | U-B 根目录及与 P-A 相同 path 前缀的节点 |
| T-1 | S-1 根目录及 `T/sub/file.txt`，owner 可与 U-A 相同 |
| T-2 | S-2 节点，故意与 T-1 使用相同或相近 path |

所有接口测试在调用前设置 `UserContext`、`TenantContext`，测试结束清理上下文。除专门的并发测试外，每个用例使用独立 tenant/数据前缀。外部 S3、MQ、缓存使用 mock/fake，并校验调用次数和参数。

### 1.2 结果口径

- 个人 API：`/api/file/**` 及由 `FileService` 暴露的 generic service 入口，只接受个人节点。
- 团队 API：必须带显式 `spaceId`，并由团队入口完成 ACL；不能因为节点 owner 恰好是当前用户而放行。
- 失败用例除校验异常类型/错误码外，还要校验数据库、配额、分片状态、S3 和事件没有发生不应发生的副作用。
- MySQL 迁移、运行中数据库和真实 S3 的结果必须记录命令输出或日志证据；mock 通过不能写成生产迁移已完成。

## 2. P0 用例

### 2.1 P0-01 generic personal API 权限边界

| 编号 | 前置数据 | 动作 | 期望结果 | 建议证据 |
|---|---|---|---|---|
| TC-P0-01-01 | U-A 访问自己的 P-A 文件 | 调用 `getNodeByIdAndOwner` / `GET /api/file/{id}` | 成功 | `FileServicePermissionIntegrationTest` |
| TC-P0-01-02 | U-B 访问 P-A 文件 | 调用个人 detail、rename、delete | 全部 FORBIDDEN/等价拒绝，节点不变 | `FileServicePermissionIntegrationTest` |
| TC-P0-01-03 | U-A 访问 T-1，且 T-1.ownerId=U-A | 调用 generic detail | 必须拒绝；owner 相同不能绕过 team scope | `FileServicePermissionIntegrationTest` |
| TC-P0-01-04 | U-A 访问 T-1 | generic rename/move/copy/delete | 全部拒绝；无 update/insert/status 变化、无配额变化、无事件 | `FileServicePermissionIntegrationTest` |
| TC-P0-01-05 | U-A 访问 T-1 | generic `/versions` list/restore、versionCount | 全部拒绝；版本表及节点不变 | `VersionServiceImpl` 测试/Mock MVC |
| TC-P0-01-06 | U-A 访问 T-1 | generic download URL、stream、ZIP 根节点 | 全部拒绝；不调用 storage download/presign，不写响应体 | `DownloadServiceImplTest` |
| TC-P0-01-07 | U-A 访问 T-1 | generic recycle list/restore/permanentDelete/empty | 不得看到或操作 T-1；删除/恢复不发生 | `RecycleBinServiceImplTest` |
| TC-P0-01-08 | U-B 具有同 tenant dataScope=2/管理员角色 | 访问 P-A 与 T-1 generic API | P-A 仍拒绝，T-1 仍拒绝；租户 dataScope 不扩大个人 owner 边界 | 权限集成测试 |
| TC-P0-01-09 | U-A 访问 T-1 | 显式团队 detail/rename/delete/download 入口 | 仅在 S-1 ACL 通过时成功；generic 收紧不误伤显式 team 入口 | `TeamController`/团队集成测试 |
| TC-P0-01-10 | 任意不存在 nodeId、recycled node | 调用 generic detail/version/download | 返回 FILE_NOT_FOUND 或既定拒绝；不泄漏其他节点信息 | 控制器集成测试 |

当前基线：`getNodeByIdAndOwner` 对 team node 仍有“由外层 TeamController 控制”注释，TC-P0-01-03～08 应在修复前复现并在修复后通过。

### 2.2 P0-02 父子 scope 一致性

| 编号 | 前置数据 | 动作 | 期望结果 | 建议证据 |
|---|---|---|---|---|
| TC-P0-02-01 | U-A、个人根 | `createFolder(0/null)` | 成功，spaceId 为空/0，owner=U-A | `FileServiceFlowIntegrationTest` |
| TC-P0-02-02 | U-A、T-1 folder | generic create folder/new blank/upload/copy target=T-1 | 全部拒绝，写入前失败 | 文件/上传集成测试 |
| TC-P0-02-03 | U-A、U-B folder | generic create/copy/move target=U-B folder | 全部拒绝，不能只依赖 `validateAccessible` | `FileServiceFlowIntegrationTest` |
| TC-P0-02-04 | S-1 folder、S-2 folder | team create/copy/move target 跨 space | 拒绝，不能产生跨 space child | 团队文件集成测试 |
| TC-P0-02-05 | S-1 folder | team create/copy/move target 同 space | 成功，child.spaceId 与 parent 一致 | 团队文件集成测试 |
| TC-P0-02-06 | 目标是 file、recycled folder、不存在 parent | create/copy/move/new blank/upload | 分别返回 BAD_REQUEST/FILE_IN_RECYCLE/FILE_NOT_FOUND；无外部调用和写入 | 相关集成测试 |
| TC-P0-02-07 | replaceFileId 属于他人/team | generic init upload | 归属/space 校验先于 S3 init 和节点变更 | `UploadStateMachineIntegrationTest` |
| TC-P0-02-08 | parentId=null、0、负值 | 各创建入口 | null/0 只解释为个人根；负值拒绝，不被转换到任意目录 | Controller/Service 测试 |

### 2.3 P0-03 path 更新与元数据事件隔离

| 编号 | 前置数据 | 动作 | 期望结果 | 建议证据 |
|---|---|---|---|---|
| TC-P0-03-01 | P-A 与 P-B 有相同 path 前缀但 owner/space 不同 | rename P-A folder | 仅 P-A 后代 path 更新；P-B 不变 | `FileServiceFlowIntegrationTest` |
| TC-P0-03-02 | T-1 与 T-2 有相同 path 前缀但 space 不同 | team rename/move T-1 folder | 仅 T-1 subtree 更新；T-2 不变 | 团队文件集成测试 |
| TC-P0-03-03 | folder rename/move | 捕获 `UPDATE_META` 事件 | 事件节点集合只含源节点和实际后代，不含 path 前缀碰巧匹配的其他 scope | `EventOutboxIntegrationTest` |
| TC-P0-03-04 | folder restore 到同/新 parent | restore | 后代 path 与 ancestor 一致，其他 owner/space 不变 | `RecycleBinPhysicalDeleteIntegrationTest` |
| TC-P0-03-05 | path 包含 `%`、`_` 或前缀相似 | rename/move | 不因 LIKE 通配符或 bare prefix 误更新 | Mapper 集成测试 |
| TC-P0-03-06 | mapper update 返回 0/部分 ID 不存在 | 更新 path | 失败语义明确；不发布未实际更新节点的事件 | Mapper/Service 测试 |

### 2.4 P0-04 上传 session 归属与团队入口

| 编号 | 前置数据 | 动作 | 期望结果 | 建议证据 |
|---|---|---|---|---|
| TC-P0-04-01 | U-A 初始化 personal upload | init → status → chunk-url → confirm → merge | 全链路成功；session、chunk、node tenant/user/space 绑定正确 | `UploadStateMachineIntegrationTest` |
| TC-P0-04-02 | U-A 拿到 uploadId，切换 U-B | status/chunk-url/confirm/merge/abort/relay-chunk/finalize | 全部拒绝；U-A session、节点、S3 不受影响 | 上传状态/relay 集成测试 |
| TC-P0-04-03 | 同 user 不同 tenant context | 使用 uploadId 操作 | 拒绝；tenant 也必须匹配 | 上传集成测试 |
| TC-P0-04-04 | 伪造 s3UploadId、fileId | 后续 status/chunk-url/merge | 以服务端 session 值为权威；伪造值不得被使用或覆盖 | Mockito verify storage |
| TC-P0-04-05 | generic init/check/simple upload 携带 `spaceId>0` | 调用 `/api/file/upload/*` | 明确拒绝；不创建 team 节点或 S3 multipart | `FileController`/上传测试 |
| TC-P0-04-06 | S-1 有 upload 权限，S-2 无权限 | 调用 `/api/team/{spaceId}/files/upload/*` | S-1 成功；S-2/无成员/无 upload 权限拒绝 | `TeamUploadController` 测试 |
| TC-P0-04-07 | session.spaceId=9001 | 通过 team route 9002 操作 | 拒绝；路径 spaceId 与 session 必须一致 | 团队上传控制器测试 |
| TC-P0-04-08 | 已完成、已 abort、已过期、FAILED、MERGING session | 重复 status/merge/abort/confirm | 结果符合状态机；已完成不误删，过期不能继续写，幂等操作不产生重复合并 | 状态机集成测试 |
| TC-P0-04-09 | S3 init 成功但 DB 写失败；S3 merge 失败 | 初始化/合并 | 事务不包外部网络；会话明确可重试或 FAILED，清理有边界且不删被引用对象 | `UploadTransactionBoundaryTest` |
| TC-P0-04-10 | `fileSize` 超限、totalChunks=0/超限、chunkSize 越界、clientLimit 越界、非法 MD5、ceil 关系错误 | init | 在 S3 和 DB 写入前拒绝 | 上传参数测试 |
| TC-P0-04-11 | 多请求并发相同 uploadId merge | 并发 merge | 最多一次 completeMultipart/finalize，节点/对象/事件不重复 | `ConcurrentUploadIntegrationTest` |
| TC-P0-04-12 | Web/desktop personal 与 team upload | 扫描调用路径并实际上传 | personal 保持 `/api/file/upload/*`，team 使用显式 team route；旧路径不能携带 team space | `rg` + 前端集成验证 |

### 2.5 P0-05 移动环和遍历防御

| 编号 | 前置数据 | 动作 | 期望结果 | 建议证据 |
|---|---|---|---|---|
| TC-P0-05-01 | P-A folder 与其 sub folder | move A → sub | 拒绝，A parent/path 不变 | `FileServiceFlowIntegrationTest` |
| TC-P0-05-02 | T-1 folder 与后代 | team move T → descendant | 拒绝；校验使用 node/scope，不信任 path | 团队文件测试 |
| TC-P0-05-03 | source/target 同一 node、source file 作为 target | move | 明确拒绝 | 文件服务测试 |
| TC-P0-05-04 | 两个不同 scope、相同 path | move | 拒绝跨 scope，不能由 path 误判 | 文件服务测试 |
| TC-P0-05-05 | 人工插入 A.parentId=B、B.parentId=A | collectDescendants/tree/size/recycle | 有 visited/深度/节点上限，快速返回明确失败，不栈溢出或无限 SQL | 文件流/回收站测试 |
| TC-P0-05-06 | 生成 >20 层或 >500000 节点夹具 | create/move/tree/size | 创建/处理达到上限时明确拒绝或标记 incomplete，不静默成功 | 规模测试 |
| TC-P0-05-07 | team move 正常同 scope、personal move 正常同 owner | move | 正常流程保持可用，后代 parent/path 一致 | 文件流程测试 |

## 3. P1 用例

### 3.1 P1-01 乐观锁/部分成功

| 编号 | 前置数据 | 动作 | 期望结果 |
|---|---|---|---|
| TC-P1-01-01 | 同一节点 version=n | 两请求并发 rename/move/update | 一个成功，一个明确冲突；失败请求不发布事件、不扣/退配额、不变更后代 |
| TC-P1-01-02 | 覆盖上传与版本快照并发 | 触发 updateById 影响行=0 | 回滚本次事务，旧文件和版本保持可读 |
| TC-P1-01-03 | editor lock 存在 | rename/move/delete/restore | 在外部 S3 前失败且无部分状态 |

建议证据：`FileServiceFlowIntegrationTest`、`UploadTransactionBoundaryTest`、`EditorCallbackTransactionBoundaryTest`，捕获 mapper/update、quota、event、storage 调用顺序。

### 3.2 P1-02 同级有效节点唯一

| 编号 | 前置数据 | 动作 | 期望结果 |
|---|---|---|---|
| TC-P1-02-01 | 同 tenant/scope/parent 两并发创建同名有效节点 | 并发 insert | 仅一个成功；另一个捕获唯一键并返回 FILE_ALREADY_EXISTS |
| TC-P1-02-02 | personal 与 team 或不同 owner/space 同 parent/name | 创建 | 不应互相冲突；scope key 隔离 |
| TC-P1-02-03 | recycled/deleted 同名存量 | 新建同名 | 依据 active_name 规则允许/拒绝符合设计；数据库唯一不把存量冲突静默删除 |
| TC-P1-02-04 | 迁移前存在 active duplicate | 执行预检和迁移 | 只输出冲突清单并停止/人工处理；不自动删除或改名 |

### 3.3 P1-03 版本号并发唯一

| 编号 | 前置数据 | 动作 | 期望结果 |
|---|---|---|---|
| TC-P1-03-01 | 同 file_node 已有版本 1..n | 并发 snapshot | version_num 无重复且连续性符合实现，唯一键无重复 |
| TC-P1-03-02 | 两请求同时 restore/snapshot | 执行并发写 | 至少一个安全重试或明确冲突；不能覆盖另一版本 |
| TC-P1-03-03 | 直接重复 insert 相同 `(file_node_id, version_num)` | insert | 数据库拒绝，服务不吞掉唯一约束异常 |

### 3.4 P1-04～P1-06 目录、统计与 ZIP

| 编号 | 前置数据 | 动作 | 期望结果 |
|---|---|---|---|
| TC-P1-04-01 | 1000+ 子节点，多层目录 | collect/tree/回收 | 使用批量/分页，SQL 次数不随每个节点线性爆炸，结果完整 |
| TC-P1-04-02 | 有环或超深树 | 目录查询/size | visited/上限生效，明确 incomplete/业务错误 |
| TC-P1-05-01 | 空文件夹、混合文件夹 | getFolderSize | size/count=0 或准确值，complete=true，calculatedAt 非空 |
| TC-P1-05-02 | 超过最大节点 | getFolderSize | complete=false 或明确失败，不能返回假完整结果 |
| TC-P1-06-01 | ZIP 预估总量≤500MiB | downloadAsZip | 预检后输出有效 ZIP，所有根节点权限正确 |
| TC-P1-06-02 | ZIP 预估总量>500MiB | downloadAsZip | 在 response 写入前失败，输出流为空/未开始写 ZIP |
| TC-P1-06-03 | ZIP 中途 storage IO 失败 | downloadAsZip | 明确错误，资源关闭；不能声称成功或输出可被当作完整 ZIP |
| TC-P1-06-04 | 混入 team/他人节点 | generic ZIP | 预检拒绝，不调用 S3，不输出部分内容 |

### 3.5 P1-07 Archive 安全

| 编号 | 前置数据 | 动作 | 期望结果 |
|---|---|---|---|
| TC-P1-07-01 | ZIP entry 名为 `../x`、绝对路径、包含反斜杠穿越 | 解压 | 拒绝或安全规范化到允许根内，禁止写出目标目录 |
| TC-P1-07-02 | 超最大 entry 数 | 解压 | 达到上限失败，释放流/临时文件 |
| TC-P1-07-03 | 单 entry/总展开大小超限 | 解压 | fail-fast，不在内存中累积全部内容 |
| TC-P1-07-04 | 深度>20、压缩比>100 | 解压 | 拒绝 zip bomb/深层目录 |
| TC-P1-07-05 | 两用户并发触发 archive，队列>50/用户 active>2 | 提交任务 | 有界拒绝/排队，不创建无界线程或任务 |
| TC-P1-07-06 | 在线预览无 view，解压上传无 upload | 调用 Archive API | 分别拒绝；权限与文件路径均校验 |
| TC-P1-07-07 | 任意 entry 解压失败、临时目录满 | 解压 | 临时文件清理，状态失败可观测，不残留半成品 |

建议证据：`ArchiveServiceIntegrationTest`、`ArchiveExtractTransactionBoundaryTest`、控制器权限测试；验证 `ArchiveSafetyProperties` 默认值和可配置上限。

### 3.6 P1-08 分片参数

| 编号 | 输入 | 期望结果 |
|---|---|---|
| TC-P1-08-01 | fileSize<0、0、超过 maxFileSize | 0 按空文件规则处理；负数/超限拒绝 |
| TC-P1-08-02 | totalChunks=0、负数、超过 maxChunks | 全部拒绝 |
| TC-P1-08-03 | chunkSize 小于5MiB、大于100MiB、非最后块与声明不符 | 拒绝；最后块允许按 fileSize 余量 |
| TC-P1-08-04 | `ceil(fileSize/chunkSize)` 与 totalChunks 不一致 | 拒绝；不创建 S3/session/chunk |
| TC-P1-08-05 | clientLimit<0 或超过配置上限 | 拒绝；服务端限速不能由客户端提高 |
| TC-P1-08-06 | MD5 非 32 位 hex、大小写/空白异常 | 按约定规范化或拒绝，不能把任意字符串作为对象 key |
| TC-P1-08-07 | 20000 chunks | batch insert 成功且不 N 次单条插入；超过上限有明确错误 |

## 4. 数据库与迁移验证

| 编号 | 验证 | 期望结果 |
|---|---|---|
| TC-DB-01 | 检查 `docker/mysql/init/40_upload_session.sql` | 首行严格为 `SET NAMES utf8mb4;`，表/索引/状态字段与 H2 一致 |
| TC-DB-02 | 检查 `docker/mysql/init/41_file_node_version_uniqueness.sql` | 首行严格为 `SET NAMES utf8mb4;`，迁移递增；包含预检、scope/active 唯一和 version 唯一策略 |
| TC-DB-03 | `mvn -pl st-core -am test -Dtest=SchemaConsistencyTest` | 通过；失败时记录完整输出 |
| TC-DB-04 | `.ai/scripts/compare-schema.ps1` 第一次 | MySQL/H2/DDL 差异有证据，退出码必须为0才可进入运行迁移 |
| TC-DB-05 | 运行中 MySQL 执行迁移并写 `schema_version` | 记录唯一版本号 YYYYMMDD.N、主题、SQL 清单、执行人、备注；不可用时标记阻塞 |
| TC-DB-06 | `.ai/scripts/compare-schema.ps1` 第二次 | 退出码0；未完成第二次对比不得标记 TEST_PASS/ACCEPT |
| TC-DB-07 | 迁移预检发现 duplicate | 迁移停止或保持可回滚，输出清单；不自动删除、改名或合并数据 |

## 5. Goal completionCriteria 映射

| Goal 完成标准 | 覆盖用例 |
|---|---|
| 个人 API 对任何 team node 无法读写 | TC-P0-01-03～08、TC-P1-06-04 |
| child 与 parent personal/team scope 一致 | TC-P0-02-01～08、TC-P0-04-05～07 |
| rename/move/restore 不修改其他 owner/space path | TC-P0-03-01～06、TC-P0-05-04 |
| 泄露 uploadId 不能操作他人会话 | TC-P0-04-02～04、TC-P0-04-07～08 |
| 目录树无法 cycle，坏数据不无限递归 | TC-P0-05-01～07、TC-P1-04-01～02 |
| 乐观锁冲突无部分成功 | TC-P1-01-01～03、TC-P0-04-09～11 |
| 同级有效节点和 version 有数据库唯一兜底 | TC-P1-02-01～04、TC-P1-03-01～03、TC-DB-02 |
| 大目录/ZIP/Archive/分片具备明确资源上限 | TC-P0-05-05～06、TC-P1-04-01～06、TC-P1-07-01～07、TC-P1-08-01～07 |

## 6. 执行顺序与证据要求

1. P0-01 → P0-02 → P0-03 → P0-04 → P0-05；每个任务先运行新增负向测试，再运行受影响回归。
2. P1-01～03 完成数据库/H2 结构同步后执行并发测试；P1-04～08 按资源风险执行。
3. 测试报告逐条引用测试编号、命令、退出码、关键日志或数据库断言；未执行项标注“实现后执行”，不写成通过。
4. 真实 MySQL/S3/对象存储不可用时保留 blocker 证据；mock 测试只证明代码路径，不证明生产基础设施迁移完成。
5. 任何测试失败都记录预期与实际、是否为基线失败、影响范围和下一步；不删除冲突数据、不用重置命令掩盖失败。
