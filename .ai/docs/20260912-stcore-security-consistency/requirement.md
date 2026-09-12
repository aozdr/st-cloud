# st-core 文件资源安全与一致性整改需求

## 1.1 功能名称

st-core 文件资源安全与一致性整改（P0-01～P1-08）

## 1.2 背景说明

本次 Review 发现文件资源的权限边界、父子树归属、上传会话和并发一致性存在高风险问题：

- 通用文件接口会直接放行团队节点，可能绕过团队成员和目录 ACL。
- 创建、复制、移动没有统一约束 parent 与 child 的 personal/team scope。
- 子树路径更新只按文本前缀匹配，可能修改同租户其他用户或其他团队空间的节点。
- 分片上传后续接口没有持久化 uploadId 与用户的绑定，客户端可影响 spaceId、fileId 和 S3 uploadId。
- 团队移动缺少目标目录是源目录子孙的判断，可能形成环。
- 乐观锁、同级重名、版本号分配缺少最终一致性兜底。
- 大目录、ZIP、在线解压和分片初始化缺少明确资源上限。

执行顺序固定为：`CORE-P0-01 → CORE-P0-02 → CORE-P0-03 → CORE-P0-04 → CORE-P0-05 → P1-01～P1-08`。

## 1.3 用户角色

| 用户角色 | 权限 | 使用场景 |
|---|---|---|
| 个人文件属主 | 读写自己的个人节点 | 个人文件详情、上传、移动、版本、下载和回收站操作 |
| 团队查看者 | 按团队 ACL 读取允许的团队节点 | 团队列表、详情、预览或下载 |
| 团队编辑者/管理员 | 按团队 ACL 执行允许的写操作 | 团队上传、重命名、移动、复制、删除、恢复 |
| 租户管理员 | 租户管理权限范围内的管理操作 | 管理接口和经授权的异常处理 |
| 未授权用户/攻击者 | 无文件资源权限 | 猜测 nodeId、uploadId、spaceId 或 s3UploadId 时必须被拒绝 |

# 二、业务需求分析

## 2.1 用户故事

- 作为个人用户，我希望个人接口只能访问我自己的个人节点，从而不能通过猜测 ID 读取或修改团队节点及其他用户节点。
- 作为团队成员，我希望团队文件必须经过显式 spaceId 和团队 ACL 校验，从而团队查看者、编辑者、非成员的行为边界稳定。
- 作为上传用户，我希望上传会话只归我所有，从而泄露 uploadId 后其他用户不能查询、签发 URL、确认、合并或中止上传。
- 作为文件系统用户，我希望目录树不会混入不同 scope，也不会形成环，从而列表、递归下载、删除和同步不会失效。
- 作为并发操作用户，我希望冲突请求明确失败且没有后续副作用，从而不会出现路径、事件、配额或版本的部分成功。

## 2.2 功能列表

| 编号 | 功能 | 描述 | 优先级 |
|---|---|---|---|
| CORE-P0-01 | 个人/团队授权边界 | 收紧个人节点读取入口；团队节点只能走显式团队入口和 ACL | P0 |
| CORE-P0-02 | 父子 scope 不变量 | 统一校验 personal parent、team parent 和根目录 | P0 |
| CORE-P0-03 | scope 安全的子树路径更新 | 使用 scope 条件或 descendant IDs 更新 path，不使用裸前缀 | P0 |
| CORE-P0-04 | 上传会话归属 | 新增 upload_session，所有后续上传操作校验当前用户和服务端 S3 上下文 | P0 |
| CORE-P0-05 | 目录环防护 | 个人和团队移动共用子孙判断，树遍历增加脏数据防御 | P0 |
| CORE-P1-01 | 乐观锁硬失败 | 所有 FileNode @Version 更新检查影响行数，冲突时阻断副作用 | P1 |
| CORE-P1-02 | 有效同级名称唯一 | 增加 personal/team scope 下的数据库唯一兜底并转换重复键异常 | P1 |
| CORE-P1-03 | 版本号并发安全 | 增加版本唯一约束并统一安全分配版本号 | P1 |
| CORE-P1-04 | 子树批量操作 | 批量读取 descendant IDs，降低递归 N+1；大操作支持批次或 job 边界 | P1 |
| CORE-P1-05 | 文件夹大小完整性 | 超过扫描上限不得返回看似完整的结果，批量接口避免串行扫描 | P1 |
| CORE-P1-06 | ZIP 预检 | 响应开始前计算总大小；超过限制或单文件失败时失败闭合 | P1 |
| CORE-P1-07 | 在线解压安全 | 限制条目、单条目、总解压量、深度、压缩比和任务并发 | P1 |
| CORE-P1-08 | 上传参数边界 | 限制文件、分片、MD5 和 clientLimit，并批量创建分片记录 | P1 |

## 2.3 业务流程

### 文件读写

```text
用户请求
  ↓
Controller 只负责路由和 RBAC；团队入口携带 spaceId
  ↓
Service 按个人或团队入口做最终资源归属校验；团队入口同时要求 ACL
  ↓
Mapper 只查询当前 tenant 和明确 scope 的节点
  ↓
返回资源或明确 FORBIDDEN/CONFLICT
```

### 分片上传

```text
个人/团队显式入口初始化
  ↓
服务端校验 scope、parent、配额、参数并创建 upload_session
  ↓
服务端保存 userId、tenantId、nodeId、spaceId、S3 uploadId
  ↓
status/chunk-url/confirm/merge/abort/relay/finalize 先校验自有会话
  ↓
使用 session 中的 nodeId 和 S3 uploadId 完成外部存储操作，再落库
```

### 树变更

```text
rename/move/restore
  ↓
校验 source、target 和 scope；拒绝 self/descendant
  ↓
按 parent_id 找到结构子树，以 descendant IDs 或带 scope 条件更新冗余 path
  ↓
FileNode 版本更新成功后才发布事件、调整缓存和配额
```

# 三、功能边界

## 包含范围

- `st-core` 文件权限、树结构、上传、版本、回收站、下载、在线解压和测试。
- `st-team` 新增明确团队上传入口或团队上传适配路由，使团队上传不再借用个人入口。
- `st-web`、`st-desktop` 将团队上传调用迁移到显式团队入口，保持个人上传接口可用。
- MySQL 递增迁移、H2 测试 schema、schema 对比和相关回归测试。
- 每个 P0/P1 TASK 完成后运行 `mvn -pl st-core test`；最终使用 `mvn -pl st-core -am clean test` 验证依赖一致性。

## 不包含范围

- 本轮不做 `FileServiceImpl` 的大规模职责拆分。
- 不改变 S3/RustFS 提供商和对象存储协议。
- 不自动删除或改写迁移前发现的重复存量数据；发现唯一约束无法直接建立时停止迁移并报告。
- 不在本轮实现 P2-01～P2-05 的架构拆分、路径缓存和版本对象回收；这些在 P0/P1 稳定后单独推进。
- 不新增与安全整改无关的 UI 视觉功能。

# 四、业务规则

- personal scope：`space_id IS NULL OR space_id = 0`，且 owner_id 必须是当前用户。
- team scope：`space_id > 0`，节点、父节点、目标节点的 space_id 必须与请求 spaceId 完全一致；团队 ACL 由团队入口校验，core Service 负责最终 scope 防线。
- `parentId=0` 或 null 表示请求 scope 的根目录，不能被解释成另一 scope 的节点。
- `parent_id` 是目录结构真相；`path` 仅是冗余字段，不得用裸 path 前缀决定资源归属。
- 个人接口 `getNodeByIdAndOwner` 只允许当前用户的 personal node；team node 一律拒绝。
- 团队上传必须从显式团队入口创建；个人上传接口携带任意有效 team spaceId 必须拒绝。
- `upload_session` 是上传后续操作的授权依据；客户端传入的 s3UploadId、fileId 不能覆盖 session 中的值，兼容字段只能被忽略或校验一致。
- 任何带 `@Version` 的 FileNode 更新必须 `rows == 1`；否则抛 CONFLICT，阻断路径、事件、配额和状态副作用。
- 同级有效节点名称在 tenant + scope + parent 下唯一；回收站/逻辑删除节点不占用有效名称。
- 同一文件节点的 version_num 在 tenant 下唯一；并发快照不得产生重复版本号。
- 递归遍历必须有 visited 集合和资源上限；发现历史脏环时抛业务异常，不允许无限递归。
- 所有文件输入必须有可配置资源上限；超限在响应体或对象写入前拒绝。
- 核心写路径不得在数据库事务内调用 S3 或其他外部网络；外部操作失败必须有清理/补偿路径。

# 五、异常场景

| 场景 | 处理方式 |
|---|---|
| 个人接口访问 team node | 返回 FORBIDDEN，不读取后续业务数据 |
| 个人用户访问他人 personal node | 返回 FORBIDDEN |
| team node 的 spaceId 与请求不一致 | 返回 FORBIDDEN |
| personal child 挂到 team parent 或反向挂载 | 返回 FORBIDDEN/BAD_REQUEST，不写入节点 |
| uploadId 不存在、过期或不属于当前用户 | 返回上传会话错误/ FORBIDDEN，不调用 S3 |
| 客户端 s3UploadId 或 fileId 与 session 不一致 | 返回 FORBIDDEN/BAD_REQUEST，以 session 为准或拒绝 |
| 目标目录是 source 自身或子孙 | 返回 BAD_REQUEST，不更新 parent_id |
| 乐观锁冲突 | 返回 CONFLICT，不更新 path、不发事件、不调配额 |
| 数据库唯一键冲突 | 转换为 FILE_ALREADY_EXISTS 或版本冲突，不返回成功 |
| 上传/ZIP/解压参数超限 | 在外部写入前返回 BAD_REQUEST/FILE_TOO_LARGE |
| 发现存量数据无法建立唯一约束 | 迁移停止；输出重复数据清单，不自动删除 |

# 六、非功能需求

## 性能

- 权限校验应在 Service 层完成，查询必须带 tenant 和 scope 条件。
- 子树查询优先使用一次批量递归 SQL 或批量 parent_id 查询；禁止新增长目录递归 N+1。
- ZIP 在输出前完成预检；在线解压线程池使用有界队列和每用户活动任务上限。
- 20 路并发同名创建只能成功一个；20 路并发版本快照不得重复版本号。

## 安全

- nodeId、spaceId、uploadId、s3UploadId 都不是单独的授权凭据。
- 不在错误信息中泄露其他用户或团队节点存在性。
- 文件名、ZIP entry、上传参数和资源大小执行服务端校验。
- 个人和团队的数据隔离必须在 Controller 之外由 Service 再次保证。

## 可维护性

- P0 先建立明确的 scope/access/session 语义，不进行机械拆类。
- 核心权限、状态流转、去重、配额和文件处理代码增加中文注释。
- 接口兼容字段保留时标记废弃语义，服务端以持久化 session/数据库状态为准。

# 七、验收标准

- Given 用户通过个人 API 提供任意 team nodeId，When 执行详情、重命名、移动、复制、删除、版本、下载或回收站操作，Then 全部拒绝且无副作用。
- Given child 和 parent 属于不同 personal/team scope，When 创建、复制、移动、上传或新建文件，Then 请求拒绝且不产生混合树。
- Given 同租户不同用户或不同团队存在相同 path，When 一个 scope rename/move/restore，Then 其他 scope 的 path 不变。
- Given B 获取 A 的 uploadId，When B 调用 status、chunk-url、confirm、merge、abort、relay 或 finalize，Then 全部拒绝且不调用 A 的 S3 会话。
- Given source 文件夹和 target parent，When target 是 source 自身或子孙，Then move 和 moveTeamFiles 都拒绝；历史脏环遍历抛业务异常而不是栈溢出。
- Given FileNode 乐观锁已被其他请求更新，When 当前请求继续 rename/move/restore/recycle，Then 返回 CONFLICT，不更新子孙 path、不发布事件、不调配额。
- Given 20 个并发同名创建或版本快照请求，When 全部完成，Then 有效同级节点或 version_num 均无重复，失败请求返回明确冲突。
- Given 大目录、超限 ZIP、Zip Bomb 或超大分片参数，When 进入统计/下载/解压/初始化，Then 在资源耗尽前拒绝或返回明确 incomplete/失败状态。

# 八、遗留问题点（Grill Me 拷打收敛）

无。以下三项已在实施前确认：

| 编号 | 问题 | 用户裁决 |
|---|---|---|
| Q1 | 本轮是否执行 P0-01～P1-08，P2 延后 | 已确认：按该范围执行 |
| Q2 | 团队上传是否改为显式团队入口 | 已确认：新增团队入口，个人入口保持个人语义 |
| Q3 | 是否授权数据库迁移及存量重复预检 | 已确认：授权；不自动删除冲突存量 |

# 九、风险分析

| 风险 | 影响 | 解决方案 |
|---|---|---|
| 新增团队上传路由导致客户端版本不一致 | High | 保留个人接口，更新 Web/桌面端；兼容期由 session 服务端校验兜底 |
| 存量同名节点阻止唯一索引 | High | 迁移前只读预检，发现冲突即停并报告清单 |
| upload_session 与旧 file_chunk 数据不一致 | High | 仅允许新会话走新校验；旧会话设置可观测的兼容策略并禁止信任客户端 S3 ID |
| 大树批处理改变事件时序 | Medium | 先保持单节点事件语义；批处理按批次验证并补集成测试 |
| 基线依赖产物不一致 | Medium | 使用 `mvn -pl st-core -am clean test`，记录与本次改动无关的基线问题 |
