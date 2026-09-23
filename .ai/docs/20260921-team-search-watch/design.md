# 团队全文搜索、文件关注与变更提醒程序设计

- 版本：1.0 / tsw-design-r1
- 作者：GPT-6；编码模型：GPT-5.6-Luna max
- 前置文档：requirement.md、uispec.md、impact.md、architecture-review.md
- 状态：实现输入定版；性能、迁移与功能测试尚待执行

## 1. 总体结构

```text
SearchPage ── GET /api/search/team ── TeamSearchService（st-search）
                                          ├─ ES：带租户/空间条件召回
                                          └─ TeamFileAccessPolicy（st-common 接口 / st-team 实现）

FileDetailPanel / FollowingPage ── /api/file-watches ── FileWatchService（st-team）

文件写事务（st-core）
  └─ ReliableEventPublisher.publishSyncChange
       ├─ 原 Outbox / 同步逻辑不变
       └─ FileWatchCaptureEvent（同步、只做数据库操作）
            └─ FileWatchCaptureListener（st-team）→ file_watch_delivery

定时任务 → FileWatchDeliveryService（短事务：核权、幂等插入 notification、标记完成）
NotificationBell → NotificationController（读取核权 / 安全目标解析）
```

不增加模块循环依赖，不改变原 MQ 消费组、投递重试和同步游标协议。新提醒在配置/未配置 MQ 两种情况下使用同一数据库投递队列。

## 2. 显式主体权限接口

在 st-common 新增 `com.stcloud.common.access.TeamFileAccessPolicy`，只使用 Long/boolean，不引用 st-core 实体：

```java
boolean isActiveMember(Long tenantId, Long userId, Long spaceId);
boolean canView(Long tenantId, Long userId, Long spaceId, Long nodeId);
```

st-team 实现 `TeamFileAccessPolicyImpl`，复用现有角色和 FolderPermissionService 权限语义。为新链路抽取/增加显式主体的解析入口，不通过修改 UserContext 模拟用户；原 `resolveMyPermissions` 对外行为保持。

权限检查顺序：

1. 参数非空且为合法正 ID；tenantId 必须与所查用户、空间、成员、节点一致。
2. 空间存在且正常；成员存在且未逻辑删除；expireAt 非空且不晚于现在时立即失效，不等待清理任务。
3. 节点属于 spaceId、正常可访问；遍历祖先至根，节点/祖先不得跨空间、跨租户、隐藏、回收、逻辑删除；循环或深度超过安全上限拒绝。文件 uploadStatus 必须完成，文件夹无此要求。
4. 角色及祖先规则使用当前 DB 数据，复用并集语义、JSON/旧 permission 兼容和 manage_settings 行为。新搜索/通知链路不得直接使用可能陈旧的共享权限缓存；可增加 `resolvePermissionsFresh`，仅请求内 memoization。
5. 普通 admin 身份不越过成员关系。

个人关注另在 FileWatchAccessService 中检查同租户、owner=userId、状态/祖先/隐藏条件。管理员不替代属主。对异步任务，tenant/user 均来自持久记录且必须再核实；不得使用默认租户 1 作为授权兜底。

DELETE 场景增加仅 st-team 内部使用的 `canViewDeletion`：只允许事件指明的被删根为回收状态，其他祖先、同租户、成员有效性与 view 规则仍需成立；不返回名称/路径。目标已永久删除或无法验证时抑制提醒。不能把“忽略删除状态”开关暴露给 HTTP。

## 3. 团队全文搜索

### 3.1 接口

新增 `GET /api/search/team`，仍要求原 `search:file` 或 ADMIN 方法权限，同时按 §2 校验当前成员。

| 参数 | 规则 |
|---|---|
| spaceId | 必填正整数；查询范围只允许该空间 |
| folderId | 可空；必须为该空间可访问的文件夹，包含其后代 |
| keyword | 必填 trim 后 1～200 字符；空词不调用 ES 全量搜索 |
| size | 默认 20，1～50 |
| cursor | 可空；下一页不接受任意 ES JSON |
| nodeType / suffixes | 兼容现有筛选语义；suffixes 数量及长度有界 |
| sizeMin / sizeMax | 非负且下界不大于上界 |
| dateFrom / dateTo | epoch ms，范围有序 |

响应沿用 Result 包装，data：

```json
{
  "records": [{"fileId":"123","fileName":"合同.docx","path":"/项目/合同.docx","nodeType":1,"fileSize":1000,"suffix":"docx","contentType":"...","createdAt":"...","updatedAt":"...","highlight":"...","spaceId":"456","parentId":"789"}],
  "hasMore":true,
  "nextCursor":"opaque-signed-token"
}
```

所有 Long ID 按项目现有序列化约定输出字符串。没有 total 字段。异常使用项目 ResultCode/BusinessException；允许新增业务码或用现有错误码配可识别消息，不能把 ES 异常吞成空页。定义文案/业务标识：SEARCH_UNAVAILABLE、SEARCH_SCOPE_TOO_LARGE、SEARCH_CURSOR_INVALID、SEARCH_CURSOR_EXPIRED；不得向客户端返回底层 ES 查询或内部地址。

旧 `/api/search` 参数和响应不删除、不强制改成游标；团队模式只调用新端点。

### 3.2 索引增量

SearchIndexInitializer 增加 `tenantId:long`、`spaceId:long`、`parentId:long`、`fileMd5:keyword`。全文/纯元数据两种写入都填充；updateMeta 同步空间与父 ID 等元数据。旧文档缺少 tenantId/spaceId 时不会匹配新团队过滤，不采用缺字段默认授权。

推荐把新团队结果按 `fileId asc` 稳定排序（字段现有类型先核实）；若 fileId 不可排序，增补专用 long 排序字段。首版不提供用户选择排序；使用唯一稳定 ID 支持 search_after。原个人相关性排序不改。

只有当前 DB fileMd5 与索引 fileMd5 一致，且名称/路径等关键元数据一致时，才允许使用正文命中及高亮。不一致的候选跳过，等待索引更新；可用 SQL 当前名称作独立回退是后续优化，不在本期加入另一套排名。

### 3.3 查询与分页算法

1. 显式从登录上下文取 tenantId/userId，成员与 folderId 验证失败直接拒绝。
2. ES bool filter 必须含 tenantId、spaceId；沿用现有文件名/正文匹配与筛选。指定 folderId 时，用已核权目录的当前 path 做 keyword 字段严格边界的 exact/prefix 初筛，避免其他目录耗尽候选预算；folderId 子树范围仍以数据库 parentId 链复核，不能只信 ES 路径前缀。
3. 每批最多 100 候选，search_after 为上次检查位置；批量读取 FileNode，维护本请求祖先与权限缓存，逐候选做 §2 校验。禁止对未经授权的候选调用 toVO 或拼接高亮。
4. 收集 size+1 条可见结果。多一条代表确有下一页；输出前 size 条，游标位置为“最后一条实际返回记录”的 sort 值，不能越过 lookahead 项。扫描到 ES 结束则 hasMore=false。
5. 最多扫描配置 `stcloud.search.team-max-candidates=2000`。尚未凑齐或无法确定下一页时达到预算，整个请求返回 SEARCH_SCOPE_TOO_LARGE，不制造假 total/假无结果。日志记录耗时和过滤数量，避免正文/关键词敏感内容进入日志。
6. 返回前再次核对成员仍有效；对已选节点做最终即时可见性检查。若过滤导致页不足，可以继续扫描剩余额度；并发权限变化时安全优先，不承诺固定快照。

游标内容包含版本、userId、tenantId、spaceId、folderId、规范化全部查询参数 hash、lastSort、过期时间。Base64URL payload + HMAC-SHA256 签名，constant-time 比较；使用独立配置密钥（可从现有已配置签名能力派生，禁止硬编码开发默认密钥）。游标只含不透明 ID/数值，不放文件名或正文。参数、签名或用户不匹配返回 invalid，超时返回 expired。测试注入固定测试密钥，不依赖生产环境。

### 3.4 升级

映射仅增量补充，应用启动不得自动全量下载所有 S3 对象。上线后通过已有管理员 reindex 入口或受控测试 fixture 重建；先检查 ES/对象存储可用性。未重建阶段新团队搜索可能少结果，UI说明索引延迟，不能放宽 tenant/space 过滤填充结果。部署文档记录是否实际执行过重建及规模。

## 4. 关注 API 与数据模型

### 4.1 HTTP

所有端点仅登录可用，主体只能来自 UserContext；不新增按用户授予的全局权限码。

| 方法与路径 | 行为与响应 data |
|---|---|
| GET `/api/file-watches/state?nodeId=...` | 核权后返回 `{nodeId,watching,watchId}`，未关注 watchId=null |
| PUT `/api/file-watches/{nodeId}` | 核权并幂等关注，返回同上；不接受其他用户 ID |
| DELETE `/api/file-watches/{nodeId}` | 仅删除当前 tenant/user 的该节点订阅，幂等；不要求节点仍存在，允许清理失效订阅 |
| GET `/api/file-watches?page=1&size=20` | 返回 PageResult records/total/current/size；按本人关注时间倒序；size 1～100 |

列表记录 `{watchId,nodeId,nodeType,spaceId,parentId,name,path,createdAt,available}`。parentId 用于当前目录定位。available=false 时 name/path/spaceId/parentId/nodeType 清空，nodeId 仅作本人取消订阅的既有键，不用于生成可访问链接。列表 total 仅计算本人的订阅数量，无跨用户统计。

### 4.2 SQL 迁移

新增 `docker/mysql/init/43_file_watch.sql`（实施时若编号被占用必须顺延并同步文档），首行 `SET NAMES utf8mb4;`。沿用现有库 utf8mb4 字符集/排序规则，不为此变更全库 collation。主键使用项目 IdWorker/ASSIGN_ID，不新增不一致的 UUID 主键方案。

`file_watch`：

| 列 | 类型/约束 |
|---|---|
| id | BIGINT PRIMARY KEY |
| tenant_id, user_id, node_id | BIGINT NOT NULL |
| created_at | DATETIME(3) NOT NULL |

唯一键 `(tenant_id,user_id,node_id)`；索引 `(tenant_id,node_id,user_id)` 用于事件匹配，`(tenant_id,user_id,created_at,id)` 用于列表。不使用逻辑删除，取消仅删除本人订阅；重新关注创建新的 id，作为订阅代际。节点范围/类型从当前 file_node 查询，不持久化可变路径。

`file_watch_delivery`：

| 列 | 类型/约束与含义 |
|---|---|
| id | BIGINT PRIMARY KEY |
| tenant_id, event_id, user_id, node_id | BIGINT NOT NULL |
| space_id, actor_id | BIGINT NULL；匿名 actor 为空 |
| change_type | VARCHAR(16) NOT NULL |
| watch_ids | TEXT NOT NULL；同用户本事件匹配的订阅 ID JSON 数组 |
| payload | TEXT NOT NULL；必要节点快照、旧父链、新父链、事件发生时间，不包含正文或凭证 |
| status | TINYINT NOT NULL DEFAULT 0；0待处理/1处理中/2重试/3已发送/4抑制/5失败待排查 |
| retry_count | INT NOT NULL DEFAULT 0 |
| next_retry_at | DATETIME(3) NOT NULL |
| last_error | VARCHAR(500) NULL；脱敏错误摘要 |
| created_at, updated_at | DATETIME(3) NOT NULL |

唯一键 `(tenant_id,event_id,user_id)`；索引 `(status,next_retry_at,id)`（全局后台调度）、`(tenant_id,user_id,node_id)`。队列表全局取待处理 ID 的 mapper 必须显式声明忽略自动租户注入，仅此调度方法允许跨租户读取最小 ID/tenant；实际处理恢复正确租户并显式 where tenant_id。也可按已存在租户遍历查询，但不得只扫描默认租户 1。

`notification` 增量可空字段 `event_id BIGINT`、`node_id BIGINT`、`space_id BIGINT`、`change_type VARCHAR(16)`；唯一索引 `(tenant_id,user_id,event_id)`。旧通知 event_id=NULL，保持原写入兼容。新 FILE_CHANGE refType=`file`、refId=nodeId；现有旧 FILE_CHANGE 无 event_id 时沿用旧映射，不以它冒充新订阅通知。

无外键级联删除现有数据。同步 st-core 测试 schema；如 st-team 测试另有 schema，也需同步。SchemaConsistencyTest 与 compare-schema.ps1 是强制门禁。

## 5. 事件捕获与覆盖

新增 core `FileWatchCaptureEvent`（或等价名称），为不可变快照，包含 eventId、tenantId、actorId、changeType、nodeSnapshot、oldParentId、occurredAt。在 ReliableEventPublisher 的 sync outbox 生成 eventId 后、原 MQ/local 分支前同步 publish；监听器仅写 DB，必须加入文件写事务，异常抛出使事务回滚，不能吞掉队列失败后声称可靠。

无事务调用必须由调用方补齐已有业务事务；捕获器遇到无事务时应明确失败或以显式 TransactionTemplate 包含对应写入，不能产生文件回滚而队列已提交的窗口。不要在监听器里调用 S3、ES、Redis、MQ 或外部 API。

MOVE 的个人和团队方法在改 parentId 之前记录 oldParentId，调用新增重载 `publishSyncChange(node, change, oldPath, oldParentId)`。旧重载保留；RENAME 不改父目录，可由当前 parentId 取父链。路径仅显示用途，不用于身份或模糊 LIKE 推断旧父节点。

监听器：

1. 从当前/旧父 ID 构建同租户同空间祖先 ID 集合，防环/有界；加入变更节点。
2. 查直接与祖先订阅。若变更根是文件夹且为 MOVE/DELETE，额外查该目录后代直接订阅（按 parentId 树，不能跨空间）；不为每个后代生成新 eventId。
3. 按 userId 聚合订阅 ID，去掉已知 actorId=userId，插入 delivery，eventId/userId 唯一。保存匹配 watchIds，后续取消/重订阅以订阅 ID 区分。
4. 匹配阶段仅决定候选，投递前仍需核权。不根据队列中的历史 fileName 直接显示通知。

写入口覆盖清单必须在 changereport 列出具体方法和测试：NewFileService、ArchiveService 解压完成、新建文件夹、UploadEventPublisher/UploadCommitManager 秒传和完成/覆盖、个人/团队重命名移动复制删除、RecycleBinService 恢复、VersionService 恢复、TextFileService 保存、EditorCallbackService 保存、st-sync 内容写入。已有事件不要重复加；只有缺少成功提交事件时补齐。

OnlyOffice 回调能从已验证编辑会话/令牌确认 actor 时使用该身份；无法确认则 actor=null，不以 file owner 伪造操作者导致漏提醒。版本恢复归类 UPDATE；同一成功操作只保留一个业务 sync/watch 事件。

## 6. 投递与历史读取

### 6.1 后台任务

`FileWatchDeliveryTask` 每 5 秒取最多 100 个待处理 ID，逐条调用独立 bean 的事务方法。调度器本身无长事务。每条处理：

- 设置当前 delivery.tenantId，完成后 finally 清理/恢复租户上下文。
- 数据库原子抢占（条件 UPDATE 或行锁）并确认最终状态，保证多个实例只有一方实际插入。实现若分离抢占与处理事务，必须有 lease/过期回收；推荐单条短事务内锁定、核权、插入与完成，无持久处理中崩溃悬挂。
- 至少一个捕获的 watchId 仍存在且属于该 user/tenant；仅匹配新订阅 nodeId 不算有效。当前用户存在且有效、非本人操作、权限满足 §2。
- 普通变更要求当前 changed node 可见；目录根不可见但仅匹配后代订阅时不得泄漏根名称，可以抑制细节。DELETE 使用专用通用不可用处理及 §2 删除核权。
- notification.eventId 唯一幂等；写通知和 delivery status=3 在同一事务。无订阅/失权等可预期情况标 status=4，不无限重试。
- 异常回滚后在独立短事务中记 retry_count/next_retry_at，退避从 5 秒至最多 5 分钟，10 次后 status=5 并错误日志；不向文件操作发起人追溯报错。进程重启扫描 pending/retry 即可恢复。

使用纯数据库权限解析，禁止事务中访问共享 Redis 权限缓存。SQL 操作可批量读取祖先/成员减少 N+1；不能以性能为由略过授权。

### 6.2 通知响应

NotificationVO 增加可空 `eventId,nodeId,spaceId,parentId,available,changeType`。eventId 作为新旧通知的兼容识别字段，仅属于当前用户的通知可读取。仅新 eventId 非空的 FILE_CHANGE 走订阅通知映射：

- 当前可见：名称及位置用当前 DB 值填充，title 使用事件类型的固定中文文案，不拼接未验证路径。
- 不可见/已删除：title=`关注内容已不可用`，content=null，available=false，目标 IDs 清空。已有存储内容不能直接透出。
- 旧 MENTION/TEAM_INVITE/MEMBER_CHANGE 及旧 FILE_CHANGE 维持原行为；本轮不改造全站通知系统。

新增 `GET /api/notification/{id}/target`，先验证 notification 属于当前 tenant/user，再实时核权，返回 `{available,nodeId,parentId,spaceId,nodeType}`；不可用时只有 available=false。新客户端根据这些数据拼接内部路由，不接受 DB 存储任意 URL。个人文件→`/files/<parentId>`，团队→`/team/<spaceId>?folderId=<parentId>&nodeId=<nodeId>`，文件夹进入自身目录。

已读接口仍限本人；取消关注不删除历史通知，通知读取仍核权。未读计数仅为本人已有通知条数，不包含其他用户或候选队列数量。

## 7. 前端实现边界

1. SearchPage 分离 personal/team 请求分支，新增 TeamSearchResultPage 类型；切换范围清游标，用序号/取消信号防止旧响应覆盖。保留个人搜索原功能。
2. TeamSpacePage 增加搜索入口并接收 folderId/nodeId 参数；对 URL 目标通过 team source 核权获取，不能直接信路径。加载面包屑使用已有 by-path/tree/get-node API。
3. FileDetailPanel 增加关注按钮，使用专用 useFileWatch；组件卸载或切换节点时不让旧请求覆盖。避免列表每行发一个查询。
4. FollowingPage 独立列表，Sidebar / 移动可达导航增加入口。使用现有 tokens、组件与 Toast，不改变全站样式。
5. NotificationBell 增加安全 target 请求；已读失败保留真实未读状态；错误、不可用不跳转。保证 keyboard/窄屏状态。

API DTO/路由以本文固定接口为准。实现需调整字段时先反馈主线程更新文档，禁止前后端各自发明不一致契约。

## 8. 实施分工与顺序

| 阶段 | 执行 | 产出 |
|---|---|---|
| 需求、影响、交互、架构、程序设计、测试用例文档 | GPT-6 | 本目录文档 |
| 后端/数据库/事件与权限、后端测试代码 | Luna max 后端 TASK | st-common/core/team/search 及 SQL/H2 |
| 前端与必要前端测试代码 | Luna max 前端 TASK（依固定契约，可与后端并行） | st-web |
| 代码/安全/体验独立检查 | 独立 reviewer，与编码者分离 | codereview/security/exp-review |
| 集成构建、测试、数据库双对比与开发库迁移 | 主线程串行协调（必要测试代码修复仍 Luna） | testreport、版本记录 |
| 知识库与最终验收 | 主线程 | 真实结果与剩余限制 |

两个编码 TASK 不并发执行 Maven 或共享构建缓存。前端可执行本目录的静态检查；后端编码阶段只准备测试，主线程在两者完成后统一执行必要测试，失败修复派回 Luna。

## 9. 验证与发布

1. 测试用例见 testcases.md；必须含权限撤销、跨租户、失效祖先、过期外部成员、HMAC 游标、无权首批命中、回滚、队列重试/重启、重复/并发投递、取消重订阅、目录根事件及每个写入口。
2. Maven 使用现有项目测试约定，覆盖 common/core/team/search 及受影响同步回归；SchemaConsistencyTest 必须运行。前端执行 package.json 现有 typecheck/build/test 命令，命令不存在不得凭空声称执行。
3. SQL 与 H2 同步后，先运行 compare-schema.ps1 记录迁移前差异；确认连接为本地已授权开发/测试库后执行新增迁移（不执行 DROP/TRUNCATE/业务数据删除）。写入唯一 schema_version=20260921.N、主题、SQL 清单、执行人及备注；N 从实际记录选择避免冲突。再次对比退出码必须 0。外部数据库未授权或不可达时记录真实阻塞，不伪造通过。
4. ES 增量 mapping + 受控 reindex；记录是否实测正文召回。没有 ES 环境时单元模拟测试不能声称 E2E 通过。
5. 回退应用时保留新增表/列；新字段可空，旧应用兼容。停止新增功能入口与 worker，不删除订阅/通知数据；索引新字段无需删除。生产部署另行授权。

## 10. 遗留事项

产品/兼容性未决事项为 0。数据库、ES、浏览器可用性在集成阶段核实；未验证环境只列为风险，不能预先声明部署完成。任何偏离上述 API、权限语义、可靠性或首版范围的实现修改由主线程更新设计 revision 后再派发。



