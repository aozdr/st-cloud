# 程序设计文档：事务边界治理

# 一、需求分析

## 功能名称

事务边界治理：S3/外部网络调用移出数据库事务 + 事务标注与超时治理

## 功能描述

```
用户（开发/平台）：
  现有 7 处核心写路径在 @Transactional 内执行 S3/外部网络 I/O，2 处只读方法误开事务，无事务超时兜底
系统行为：
  S3/外部网络调用在事务外执行；DB 写收窄进事务；删除类走提交后异步补偿；只读方法不开事务；长事务有超时
最终结果：
  网络耗时不再占用 DB 连接；失败不留孤儿对象；规范固化到 AGENTS.md
```

# 二、系统影响分析

## 影响模块

| 类型 | 模块 | 是否修改 |
|------|------|---------|
| 后端 | `st-core`（UploadServiceImpl / FileObjectService / RecycleBinServiceImpl / ArchiveServiceImpl / EditorCallbackServiceImpl / TextFileServiceImpl） | 修改 |
| 后端 | `st-sync`（SyncBlockServiceImpl） | 修改 |
| 后端 | `st-share`（ShareServiceImpl） | 修改 |
| 配置 | `st-api` application.yml（事务默认超时） | 修改 |
| 规范 | `AGENTS.md` / `.ai/knowledge/conventions.md` | 修改 |
| 数据库 | 无表结构变更（复用 `event_log` outbox） | 不修改 |
| 前端 | 无 | 不修改 |

## 影响文件预测

- 新增：无新表；`FileObjectService` 增加非上传变体方法（同文件内）
- 修改：上述 service 实现类、application.yml、AGENTS.md、conventions.md
- 删除：无

# 三、整体设计方案

## 3.1 事务边界原则（写入规范，F7）

1. S3/外部网络调用一律在事务外执行；DB 写一律在事务内
2. 删除类：DB 事务内引用归零 + 记录待删状态（outbox 事件），提交后异步删 S3，失败进补偿队列重试
3. 只读查询方法禁止 `@Transactional`；确需一致性快照用 `readOnly=true`
4. 长事务显式 `timeout`，全局默认 `spring.transaction.default-timeout=30s`
5. 半成品对象统一 `tmp/` 前缀，失败尽力删除 + 定时清理兜底

## 3.2 补偿机制（P1 落地方案）

复用现有 outbox：`ReliableEventPublisher` 新增 `publishPhysicalDelete(node, storagePath)`，在 DB 事务内写入 `event_log`（`eventType=PHYSICAL_DELETE`，payload 含 storagePath/md5/tenantId），提交后由 `EventRelay`（AFTER_COMMIT）投递 MQ；消费端删除 S3 对象，失败按现有 `status=2` 重投机制重试。

## 3.3 逐项改造设计

### F1-1 `ShareServiceImpl.getDownloadUrl`（st-share）

- 现状：纯读 + 生成预签名 URL，`@Transactional` 无必要
- 改造：删除 `@Transactional`；逻辑不变
- 测试：事务拦截断言（自定义 AOP 测试或日志验证）确认不再开启事务

### F1-2 `SyncBlockServiceImpl.blockCheck`（st-sync）

- 现状：只读 DB + 事务内 S3 `initMultipartUpload`
- 改造：删除 `@Transactional`；S3 init 移至读取块布局前后均可（无 DB 写，无事务）；S3 失败直接返回错误
- 测试：单测覆盖"无事务开启"与 S3 失败路径

### F1-3 `UploadServiceImpl.checkInstantUpload`（st-core）

- 现状：非秒传路径只读也开事务；秒传命中才写
- 改造：拆为两段——只读检查（无事务）+ 秒传创建（独立事务）。秒传创建逻辑迁至独立 bean（如 `uploadManager.createInstantNode(...)` 或 `FileObjectService` 协作方法），保证 Spring 代理生效；原方法仅做只读检查并调用它
- 测试：非命中路径无事务；命中路径事务内完成引用+节点+配额

### F2-1 `UploadServiceImpl.simpleUpload`（st-core）

- 现状：`fileObjectService.acquire` 的 supplier 内 S3 上传在事务中执行（限速上传）
- 改造：
  1. 事务外：MD5 计算、配额/容量预检、`fileObjectService` 新增 `acquireByPath(tenantId, md5, size, storagePath)`（不触发上传，仅 DB 引用/记录）
  2. 事务外：先查询对象是否已存在——不存在则上传到 `tmp/` 前缀 key（限速逻辑保留）
  3. 事务内：`acquireByPath` 建对象记录/加引用 + 插入节点 + 扣配额 + 发事件
  4. 失败清理：事务回滚后尽力删除本次上传的 tmp 对象（由调用方在 catch 中清理，定时任务兜底）
- 测试：S3 超时不断连；DB 失败后 tmp 对象被清理或进入兜底

### F2-2 `UploadServiceImpl.mergeChunks`（st-core）

- 现状：S3 `completeMultipart` / `abort` / `deleteObjectQuietly` 在事务内
- 改造：
  1. 事务外：幂等检查 + `claimMerging`（Redis 认领）+ S3 `completeMultipart`
  2. S3 失败：`handleMergeFailure` 收敛为独立小事务（仅标记 FAILED/清理残留），随后抛错
  3. 事务内：`markChunksMerged` + `acquireByPath` + 节点更新 + 版本快照 + 差值配额 + 事件
- 测试：并发幂等（现有测试覆盖）；S3 失败路径连接释放

### F6-1 事务超时与只读标注

- `st-api/application.yml`：`spring.transaction.default-timeout: 30s`（全局兜底）
- 已知长事务（第二迭代 F4/F5 改造后）显式 `@Transactional(timeout=60)` 或按需调整
- 只读方法按 F1 处理；其余写方法保持默认 30s

### F6-2 规范固化（F7）

- `AGENTS.md`「代码修改强制约束」新增第 8 条：核心写路径禁止在事务内做 S3/外部网络调用
- `.ai/knowledge/conventions.md` 增补事务边界小节（原则 + 反例清单）

### 第二迭代（F3/F4/F5，P3 定版）

### F3 `SyncBlockServiceImpl.blockUpload`（st-sync）

- 现状：事务内循环 `UploadPartCopy` + `completeMultipartUpload`
- 改造：S3 复制循环 + complete 移出事务；事务内统一落 DB（acquire + 节点 + 版本快照 + 块布局 + 配额 + 事件）

### F4 回收站永久删除系列（st-core）

- 现状：事务循环内 `deletePhysical` / `deleteObject`（S3 网络）
- 改造：事务内只做 DB（引用归零、配额退还、删除记录、发布 `PHYSICAL_DELETE` outbox 事件）；S3 物理删除由消费者异步执行，失败重试
- 兼容：`ref_count>0` 的对象不发布删除事件（引用未归零）

### F5 解压/回调/文本覆盖（st-core）

- `ArchiveServiceImpl.extractArchive`：改为 ZIP 先下载到临时文件（事务外）→ 预检统计 → 逐条目上传 `tmp/`（事务外）→ 一个事务内插入节点/扣配额；失败清理 tmp + 定时兜底
- `EditorCallbackServiceImpl.handleCallback`：先下载临时文件 + 上传 S3（事务外）→ 事务内更新节点/配额/事件
- `TextFileServiceImpl.overwriteContent`：先上传 S3（事务外）→ 事务内更新节点/配额/版本

# 四、前端设计

不适用（本需求无前端改动）。

# 五、后端设计

- API 契约：所有对外接口路径、请求/响应结构不变
- 数据模型：`file_object` / `file_node` / `event_log` 结构不变；`event_log` 新增事件类型枚举值 `PHYSICAL_DELETE`
- 业务流程：见 3.3 逐项设计
- 异常处理：S3 失败直接返回失败（事务未开启）；DB 失败由事务回滚 + tmp 清理

# 六、数据库设计

- 无表/字段/索引变更
- 复用 `event_log`：`eventType=PHYSICAL_DELETE`，payload 为 JSON（storagePath、md5、tenantId、refCount）
- 无需迁移脚本；不触发 schema 一致性流程

# 七、安全设计

- 权限校验位置不变：所有改造保持"先校验后操作"
- 补偿队列幂等：消费者以 `eventLogId` 为幂等键；删除前再次校验 `ref_count=0`
- tmp 前缀对象仅限本租户路径，删除前校验归属

# 八、性能设计

- 目标：上传/解压/回调路径的 DB 连接占用从"网络耗时"收敛为"DB 写耗时"
- 补偿队列与定时清理不阻塞主流程
- 全局事务超时 30s 防连接无限占用

# 九、开发计划

```text
第一迭代（低中风险）：F1 / F2 / F6 / F7
  BE-01（st-core）：checkInstantUpload 拆分 + simpleUpload + mergeChunks（含 FileObjectService acquireByPath）+ 单测
  BE-02（st-sync + st-share）：blockCheck 去事务 + getDownloadUrl 去事务 + 单测
  BE-03（配置与规范）：application.yml 默认超时 + AGENTS.md/conventions.md 规则 + 无代码测试
  → 主线程合并后 mvn test（st-core/st-sync/st-share）+ 构建验证
第二迭代（失败语义）：F3 / F4 / F5
  BE-01（st-sync）：blockUpload 复制循环移出事务
  BE-02（st-core）：回收站永久删除异步补偿（outbox PHYSICAL_DELETE + 消费者）
  BE-03（st-core）：extractArchive / handleCallback / overwriteContent 改造
  → 主线程合并后 mvn test 全量 + 集成验证
```

> 两个迭代均按 V15 worktree 隔离并行派发，文件零重叠；`mvn test` 由主线程合并后串行执行。

# 十、遗留问题点（Grill Me 拷打收敛）

| 编号 | 遗留问题 | 影响 | 建议方案 | 用户裁决 |
|------|---------|------|---------|---------|
| D1 | `acquireByPath` 拆分后"先上传后建对象记录"的并发窗口：两请求同时上传同一 md5，均未命中对象，冗余上传后 insertIgnore 竞争 | 冗余对象残留 | 维持现状容忍 + 失败方按自身 tmp key 尽力删除；定时清理兜底（需求 P2 已定） | 待确认 |
| D2 | `mergeChunks` 的 S3 complete 移出事务后，claim 已标记但进程崩溃（S3 未完成）的恢复路径 | 合并状态悬挂 | 保留 claim + 现有 handleMergeFailure/超时回收机制；S3 完成后事务失败的对象残留走 tmp 清理 | 待确认 |
| D3 | `PHYSICAL_DELETE` 消费者重试耗尽后的处理 | 物理对象长期残留 | 沿用 event_log `status=2` 重投机制，重试上限与现有事件一致；耗尽后保留记录供人工清理 | 待确认 |

# 十一、风险分析

| 风险 | 影响 | 解决方案 |
|------|------|---------|
| 上传/删除核心路径改造回归 | 上传/回收站不可用 | 逐项独立修复 + 现有上传/回收站集成测试回归 |
| 孤儿对象/引用泄漏 | 存储与配额漂移 | tmp 清理 + PHYSICAL_DELETE 补偿 + 定时任务兜底 |
| 删除顺序调整影响一致性 | 对象被引用或提前删除 | 删除前校验 ref_count=0；事件幂等键防重复删 |
| 并发上传冗余 | 临时存储浪费 | insertIgnore 竞争后失败方清理自身对象（D1） |
