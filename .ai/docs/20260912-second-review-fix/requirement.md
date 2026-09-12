# 需求文档

## 1.1 功能名称

st-cloud 第二轮 Review 聚焦修复：哈希、同名 scope、上传事务/状态机、回收站幂等与 Archive 输入上限

## 1.2 背景说明

基线 `11ef6c2836af548c52052e5860511242741d9d1d` 的 Web 大文件哈希仍为“前 2MB + 文件大小”，Desktop 上传仍调用首/中/尾采样哈希；服务端把 `fileMd5` 用于秒传和对象复用，可能把不同文件误判为同一对象。

同名查询只按 tenant、parent、name 检查，未区分个人 owner 与团队 space。分片初始化的数据库写入、UploadSession 状态更新、回收站递归删除和 Archive ZIP 临时下载也存在一致性或资源边界问题。

## 1.3 用户角色

| 用户角色 | 权限 | 使用场景 |
|---|---|---|
| 个人用户 | 操作本人个人空间文件 | 上传、重命名、移动、复制、恢复、清空回收站 |
| 团队成员 | 在已授权团队空间操作文件 | 团队文件夹创建、上传、移动、复制 |
| 系统维护者 | 执行既有回收站清理任务 | 清理过期回收站节点 |

# 二、业务需求分析

## 2.1 用户故事

- 作为上传用户，我希望同一文件在 Web 与 Desktop 得到相同的完整 MD5，避免不同文件被错误秒传或复用对象。
- 作为个人用户或团队成员，我希望同名判断只在当前 owner/space 内生效，不被其他用户或团队空间阻塞。
- 作为上传用户，我希望初始化失败时不留下半套节点、会话、分片记录或 S3 multipart。
- 作为并发上传操作者，我希望 merge 与 abort 只能由一个合法状态迁移获胜。
- 作为回收站用户，我希望父子节点重复出现在输入集合时只退还一次配额。
- 作为 Archive 操作者，我希望超大 ZIP 在临时盘写满前被拒绝并清理临时文件。

## 2.2 功能列表

| 编号 | 功能 | 描述 | 优先级 |
|---|---|---|---|
| P0-01 | 完整文件 MD5 | Web/Desktop 对全部文件字节流计算 MD5；`fileMd5` 不含文件大小、不使用采样 | P0 |
| P1-01 | 完整同名 scope | 个人按 tenant+owner+parent，团队按 tenant+space+parent 检查 | P1 |
| P1-02 | Upload Init 原子化 | S3 init 在事务外；node/version/session/chunks 在同一 DB 事务 | P1 |
| P1-03 | UploadSession CAS | ACTIVE/FAILED、MERGING、ABORTED 等状态迁移均条件更新并检查 affected rows | P1 |
| P1-04 | 回收站幂等 | 只处理 recycle roots，并以实际删除结果决定 quota/object side effect | P1 |
| P1-05 | Archive 输入上限 | ZIP 下载到临时文件采用 bounded copy，超限立即删除临时文件并失败 | P1 |
| P2-SMALL | simpleUpload 文案 | 错误提示与现有 100MB 阈值一致 | P2 |

## 2.3 业务流程

### Phase 1：哈希

```text
用户选择文件
  ↓
Web/Desktop 流式读取全部字节
  ↓
生成完整 MD5(file bytes)
  ↓
check/init 使用 fileMd5；服务端不得把采样值当内容指纹
```

### Phase 2-3：scope 与 Upload Init

```text
请求进入
  ↓
按个人 owner 或团队 space 校验同名与父目录
  ↓
配额/容量预检
  ↓
S3 initMultipart（事务外）
  ↓
UploadInitCommitManager @Transactional
  ↓
replacement FOR UPDATE → snapshot → node → session → chunks
  ↓
失败回滚 DB，并 best-effort abort S3
```

### Phase 4：会话状态机

```text
merge/abort 请求
  ↓
加载当前用户拥有的 UploadSession
  ↓
CAS 认领权威状态
  ↓
仅 merge 认领成功后执行 S3 complete
  ↓
事务完成 DB finalize；失败转 FAILED 并记录补偿
```

### Phase 5-6：删除与 Archive

```text
清空回收站
  ↓
查询没有 RECYCLED ancestor 的 roots
  ↓
递归删除；每个节点只有首次有效删除才退 quota/释放对象

Archive extract
  ↓
按 maxArchiveInputSize bounded copy 到临时文件
  ↓
超限立即删除临时文件并抛 FILE_TOO_LARGE
  ↓
未超限才进入 summarizeArchive
```

# 三、功能边界

## 包含范围

- 计划列出的 P0/P1 六类问题和必要回归测试。
- simpleUpload 100MB 错误文案一致性。
- 仅修改实现所需的后端、Web、Desktop、测试和本轮 `.ai` 文档。

## 不包含范围

- FileServiceImpl 大规模拆分、微服务化、权限系统重设计。
- UI 视觉、页面布局或交互改版。
- 文件对象模型重构、SHA-256 迁移、P2 性能优化。
- 自动清理历史冲突数据或删除现有测试。
- 未经单独确认的公开 API、数据库 schema 或 migration 变化。

# 四、业务规则

1. `fileMd5` 永远表示全部文件字节的 MD5；不得拼入文件大小，不得使用首/中/尾采样。
2. 个人 scope：`tenant_id + owner_id + parent_id + name + NORMAL + deleted=0`，`space_id` 为空或不大于 0。
3. 团队 scope：`tenant_id + space_id + parent_id + name + NORMAL + deleted=0`。
4. DB 事务内不得调用 S3 或进行大文件网络 IO；S3 init 失败或 DB 提交失败必须按既有补偿约定处理并记录日志。
5. UploadSession 是 merge/abort 的权威状态；所有状态转换必须用条件更新并要求 `affectedRows == 1`。
6. abort 不得打断已进入 MERGING 的会话；重复 abort 只能产生一次 cleanup side effect。
7. 回收站清空只处理没有 RECYCLED ancestor 的根；实际删除成功前不得退还配额或释放引用。
8. Archive 输入流复制总字节超过配置上限时必须删除临时文件，且不能进入 ZIP summarize。

# 五、异常场景

| 场景 | 处理方式 |
|---|---|
| 客户端哈希计算失败 | 上传任务失败，不发起 check/init |
| 同 scope 同名 | 返回既有文件冲突错误；数据库唯一约束继续兜底 |
| Upload Init 任一 DB 写入失败 | 整体回滚；S3 multipart best-effort abort |
| merge CAS 失败 | 返回冲突/不可操作，不执行 S3 complete |
| abort 看到 MERGING | 返回 409/CONFLICT，不执行 abort cleanup |
| 重复永久删除 | 第二次无有效删除行，不重复退 quota/refCount |
| ZIP 输入超限 | 抛 `FILE_TOO_LARGE`，删除临时文件，不进入 summarize |

# 六、非功能需求

## 性能

- Web/Desktop MD5 必须流式处理，不能因全量 hash 把整个文件加载进内存。
- Upload Init 的事务只覆盖 DB 写入，不能包住 S3 初始化或大文件 IO。
- Archive 输入限制应在临时文件写入过程中生效。

## 安全

- 所有现有后端 owner/tenant/space 权限校验继续保留，客户端 `storagePath`、`s3UploadId` 不作为授权凭据。
- 不扩大查询 scope，不允许跨用户或跨团队空间同名误判。

## 可维护性

- 状态迁移集中在可测试的 Mapper/服务方法中。
- 核心权限、状态、配额、文件处理路径补充中文注释。

# 七、验收标准

- Given 同一文件为 0B、1KB、5MB、11MB、100MB+，When Web 与 Desktop 计算 `fileMd5`，Then 结果与服务端 fixture 的完整 MD5 一致。
- Given 两个同大小文件共享前 2MB 但后续不同，When 计算 `fileMd5`，Then 两者不同且不会错误秒传。
- Given 不同个人用户或不同团队 space 使用相同 parent/name，When 创建或上传，Then 均允许；同一 scope 仍冲突。
- Given init DB 的任一 chunk batch 失败，When 请求返回，Then node/session/chunks/version snapshot 全部回滚。
- Given merge/abort 并发竞争，When 一个会话先 CAS 到 MERGING，Then abort 返回冲突且 merge 只 complete 一次。
- Given 父子节点均为 RECYCLED，When emptyRecycleBin 或重复 permanentDelete，Then quota/refCount/object cleanup 不重复。
- Given Archive 输入流超过 maxArchiveInputSize，When extract，Then 在完整写入临时盘前失败、删除临时文件且不调用 summarizeArchive。
- Given 本轮全部代码完成，When 执行计划要求的 backend/frontend/desktop/schema/git 门禁，Then 记录真实通过/失败数量，不以删除测试绕过失败。

# 八、遗留问题点（Grill Me 拷打收敛）

经过范围、边界、异常、数据/API 影响和风险收敛，本轮没有待用户裁决的未定问题。以下决策固定执行：

| 编号 | 遗留问题 | 影响 | 建议方案 | 用户裁决 |
|---|---|---|---|---|
| - | 无 | - | 按外部修复计划执行；无 schema/API 扩展，除非实现证明无法完成且另行确认 | 已由执行指令固定 |

# 九、风险分析

| 风险 | 影响 | 解决方案 |
|---|---|---|
| 完整 MD5 增加客户端等待时间 | 大文件上传启动变慢 | 使用流式读取，不改变上传协议；以正确性优先 |
| 同名 scope 改动遗漏调用点 | 仍会跨 scope 误报 | 逐项覆盖 create/rename/move/copy/upload/restore/archive/team 调用链并补测试 |
| CAS 与已有节点状态迁移不一致 | 并发请求错误或遗留状态 | 先梳理既有状态常量，再新增条件更新测试 |
| S3 补偿失败 | 遗留 multipart/object | 保留 warn/error 日志并沿用后台补偿，不静默吞错 |
