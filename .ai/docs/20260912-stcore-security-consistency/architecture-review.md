# 架构设计评审

## 一、架构评审基本信息

```text
功能名称：st-core 文件资源安全与一致性整改（P0-01～P1-08）
业务背景：通用入口、树 scope、上传会话和并发控制存在越权或数据不一致风险。
目标：以 Service 为最终防线，修复个人/团队边界、树结构、上传会话和 P1 资源控制。
涉及模块：st-core、st-team、st-web、st-desktop、MySQL/H2、Outbox/事件。
```

## 二、影响范围

| 类型 | 模块 | 影响 |
|---|---|---|
| 前端 | st-web/st-desktop | 团队上传路由、错误状态、恢复参数 |
| 后端 | st-core | 权限、树变更、上传、版本、下载、解压 |
| 后端 | st-team | 团队上传入口和每次会话操作 ACL 复核 |
| 数据库 | MySQL/H2 | upload_session、有效同级唯一、版本唯一、批量查询索引 |
| 缓存 | core accessible/folder-size | key scope 和 incomplete 结果 |
| 第三方服务 | S3/RustFS | 只接收服务端 session 解析出的 key/uploadId |

## 三、需求理解评审

```text
业务目标：阻断通用 API 越权和 scope 污染，并让并发/大资源操作失败可见、可恢复。
核心流程：请求认证 → 入口确定 personal/team scope → Service 最终校验 → Mapper 带 scope 查询 → 数据变更成功后事件。
成功标准：满足 requirement.md 的 8 项 completion criteria，并完成 P0/P1 测试门禁。
限制条件：P0 前不拆 FileServiceImpl；不自动删除迁移冲突数据；数据库迁移必须先预检。
```

## 四、整体架构设计

```text
用户
 ↓
st-web / st-desktop
 ↓
st-api 聚合应用
 ↓
个人 FileController 或团队 TeamController/TeamUploadController
 ↓
st-core Service（scope/session/version/资源限制最终防线）
 ↓
MyBatis Mapper + MySQL/H2
 ↓
Outbox / S3（外部操作在事务外）
```

- `st-core` 不依赖 `st-team`，因此不在 core 注入团队实现；团队 ACL 由 `st-team` 入口检查，core 只验证节点和 parent 的 team scope 一致。
- 新增 `TeamUploadController` 放在 `st-team`，调用 `UploadService` 的团队受控方法；后续接口先根据 uploadId 读取自有 session，再由 controller 用 session 的 nodeId 复核团队 ACL。
- 个人通用入口不再接收 team scope；服务层对所有 personal-only 方法拒绝 team node。
- `parent_id` 是目录结构真相，path 只由结构变更流程按 ID 集合或明确 scope 条件同步。

## 五、技术方案评估

| 技术 | 选择 | 原因 | 替代方案 |
|---|---|---|---|
| scope | 小型 `FileScope`/校验方法 | 不改现有实体关系即可集中规则 | 在 FileNode 中增加复杂领域层，范围过大 |
| 上传会话 | MySQL `upload_session` | 多实例共享、可审计、可过期 | 仅 Redis：重启/一致性和审计不足 |
| 子树查询 | MySQL 8 `WITH RECURSIVE` + 批量 ID | 一次查询替代 N+1，parent_id 保持真相 | 逐层 Java 查询：大目录慢 |
| 同级唯一 | generated `scope_key` + nullable `active_name` 唯一索引 | personal/team 共用索引且回收站可同名 | 仅 Java SELECT+INSERT：有竞态 |
| 版本分配 | 锁定 file_node 行后取 max+1 | 与现有版本模型兼容，唯一约束兜底 | 仅应用内锁：多实例失效 |
| 解压限制 | `@ConfigurationProperties` + 流式临时文件 | 可运维调整且不把 entry 全量放内存 | 固定常量/ByteArrayOutputStream：无法治理 |
| 线程池 | bounded `ThreadPoolExecutor` + per-user counter | 防止无限排队和单用户占满 | 固定线程池无界队列：仍会堆积 |

评估：方案符合 Spring Boot 3.2、MyBatis-Plus、MySQL 8 和现有 S3/Outbox 架构；新增组件只覆盖边界规则，不提前做 P2 大拆分；接口兼容通过新增团队命名空间和保留旧字段实现。

## 六、后端架构评审

### 分层设计

- Controller：认证、RBAC、团队 ACL、参数绑定；不承担资源归属的最终判断。
- Service：personal/team scope、session owner、parent-child invariant、冲突处理和资源上限；所有写入口必须执行。
- Mapper：显式 tenant + owner/space 条件，提供批量 descendant、锁行和带 scope 的 path 更新。
- Domain：保持 `FileNode`、`FileVersion`，新增 `UploadSession`、`FileScope`/内部校验对象和安全配置属性。

### 事务边界

- 个人/团队节点变更：数据库更新、配额和 Outbox 在事务内；S3 不在事务内。
- 分片上传：初始化先创建服务端 session；S3 multipart init 在事务外，成功后落库 session/node；merge 先校验 session，再完成 S3，最后事务内 finalize。
- abort/delete：数据库状态更新与补偿事件在事务内，S3 abort/delete 在提交后或独立补偿流程执行。
- 版本分配在已有业务事务中锁定 file_node 行，避免两个事务拿到同一 next version。

### 并发、幂等和异常

- `FileNode @Version` 更新必须检查 `rows == 1`，统一抛 CONFLICT。
- DB 唯一索引是同级名称和版本号的最终兜底，Java 捕获 `DuplicateKeyException`。
- upload_session 状态迁移采用状态条件更新；重复 merge/abort 按已完成/已中止幂等返回。
- 递归查询和树构造均有 visited、最大深度和最大节点数。

## 七、前端架构评审

```text
页面：个人 FileBrowser、TeamSpacePage、上传队列、桌面同步队列
组件：复用 useUpload/upload-manager，不新增视觉组件
状态：保留现有上传状态；增加 session-expired/conflict/limit 错误分类
接口依赖：个人 /file/upload/*；团队 /team/{spaceId}/files/upload/*
```

客户端上传 metadata 必须记录 `spaceId` 和入口类型，以便暂停/恢复时使用正确命名空间；服务端仍不信任客户端 S3 ID。

## 八、数据库设计评审

### 表

`upload_session`：

```text
id BIGINT PK
tenant_id BIGINT NOT NULL
upload_id VARCHAR(200) NOT NULL UNIQUE
user_id BIGINT NOT NULL
file_node_id BIGINT NOT NULL
space_id BIGINT NULL
storage_path VARCHAR(500) NOT NULL
s3_upload_id VARCHAR(200) NOT NULL
status TINYINT NOT NULL
expire_at DATETIME NOT NULL
created_at/updated_at DATETIME NOT NULL
deleted TINYINT NOT NULL DEFAULT 0
```

索引：`(tenant_id,user_id,status,expire_at)` 用于用户会话清理，`(tenant_id,file_node_id)` 用于节点关联，`(upload_id)` 唯一用于后续接口。

### 约束

- `file_node.scope_key`：personal 使用 ownerId，team 使用 spaceId。
- `file_node.active_name`：正常且未删除时为 name，否则为 NULL。
- 唯一索引：`(tenant_id, scope_key, parent_id, active_name)`。
- `file_version` 唯一索引：`(tenant_id,file_node_id,version_num)`。
- 迁移前执行只读重复检查；有冲突不建立索引，不改数据。

## 九、缓存设计

| 缓存对象 | Key | 过期 | 更新 |
|---|---|---|---|
| 结构可用性 | `acc:{nodeId}` | 30 秒 | parent/status 变更失效；不表示用户授权 |
| 文件夹统计 | `fsize:{tenant}:{scope}:{nodeId}` | 5 分钟 | 结构写变更失效或 TTL；结果带 complete/calculatedAt |
| 上传会话 | 不使用应用缓存作为权威 | — | 以 DB session 为准，可选只读短缓存但不能绕过 owner 校验 |

缓存不能替代权限；key 加 tenant/scope 只是避免误用，DB 校验仍必须执行。

## 十、高并发设计评审

```text
预估压力：上传后续接口、文件列表和统计高频；并发创建/版本快照测试 20 路；大目录 1万/10万节点。
瓶颈：SELECT+INSERT 竞态、逐节点查询、ZIP 输出后才发现超限、无界解压任务。
解决方案：数据库唯一约束、锁行版本分配、CTE/批量 ID、ZIP preflight、有界 executor、每用户任务数。
```

## 十一、安全设计评审

- JWT/Security 继续负责身份和粗粒度权限。
- Service 严格区分 personal/team；团队入口复核 spaceId、nodeId、ACL 和 parent。
- upload_session 绑定 tenant/user/node/space/S3；后续接口不使用客户端 S3 ID 作为授权。
- ZIP entry 拒绝绝对路径、盘符、`.`、`..`，每段统一 FileNameSanitizer。
- 错误响应不泄露其他 scope 的节点存在性。
- 所有新 SQL 使用参数绑定；scope 条件不能由字符串拼接。

## 十二、异常和容错设计

| 异常 | 处理方案 |
|---|---|
| S3 不可用 | 不提交 finalize；保留/标记 session 状态，按现有补偿清理 |
| 数据库异常 | 事务回滚；不发布事件；外部对象由提交后补偿清理 |
| 网络中断 | upload session 可查询/恢复；预签名 URL 重新签发 |
| 重复请求 | session/node 状态条件更新和唯一键保证幂等 |
| 存量约束冲突 | 迁移前预检失败，停止迁移，人工处理清单 |
| 历史目录成环 | visited/深度/节点上限后抛 BusinessException |

## 十三、架构风险分析

| 风险 | 影响等级 | 解决方案 |
|---|---|---|
| 团队 API 迁移遗漏客户端 | High | 以路由常量/集成测试扫描 Web/桌面调用 |
| generated column 在 MySQL/H2 语法差异 | High | 先在 H2 和目标 MySQL 版本执行 schema；迁移前不标成功 |
| 版本锁行与外层事务传播不一致 | High | allocator 统一入口 + 并发集成测试 |
| CTE 受脏数据影响 | High | cycle guard、最大规模限制和专门坏数据测试 |
| 大 entry 临时文件耗尽磁盘 | High | 单条/总量上限、临时目录配额监控、失败清理 |

## 十四、架构评审结论

```text
架构评分：通过，8/10
技术方案：先做 P0 权限/树/session，再做 P1 并发/批量/资源安全；团队上传新增显式 namespace。
主要风险：API 迁移、存量唯一约束和大文件临时资源。
优化建议：P0/P1 稳定后再执行 P2 Service 拆分、路径缓存和版本对象引用回收。
是否进入开发：架构允许进入程序设计；需完成 design.md 与 testcases.md 确认门禁。
```

## 十五、遗留问题点

无。范围、团队上传入口、数据库预检和不自动删数据方案已由用户确认。
