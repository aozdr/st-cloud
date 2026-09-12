# 架构设计评审

## 一、架构评审基本信息

```text
功能名称：st-cloud 第二轮 Review 聚焦修复
业务背景：客户端指纹、同名 scope、上传事务/并发、回收站和 Archive 输入边界仍有一致性风险
目标：按 Phase 1→7 收口现有 P0/P1，不扩大模块边界
涉及模块：st-web、st-desktop、st-core、st-team、现有 MySQL/H2 测试
```

## 二、需求理解评审

```text
业务目标：消除错误秒传、跨 scope 重名、半初始化上传、merge/abort 竞态、重复退配额和临时盘耗尽风险
核心流程：完整 MD5 → scoped name check → S3 init + DB atomic commit → session CAS merge/abort → root-only delete → bounded archive copy
成功标准：计划第 13 节 DoD 与第 10 节测试清单全部有真实证据
限制条件：严格 Phase 顺序；不改 UI；不做无关重构；S3 不进入长 DB 事务；不自动清理存量数据
```

## 三、整体架构设计

```text
Web / Desktop
    ↓ 完整 fileMd5
Upload Controller / File APIs
    ↓ tenant + owner/space scope
st-core UploadService / FileService / ArchiveService
    ├─ S3 init/complete/abort（事务外）
    ├─ UploadInitCommitManager（仅 DB 事务）
    ├─ UploadSession CAS Mapper
    └─ Recycle root / bounded archive safeguards
    ↓
MySQL/H2：file_node、upload_session、file_chunk、file_version
```

## 四、技术方案评估

| 技术 | 选择 | 原因 | 替代方案 |
|---|---|---|---|
| 哈希 | Web SparkMD5 分块 + Desktop Node crypto stream | 保持现有栈，完整读取且不全量驻留内存 | SHA-256：超出本轮范围 |
| scope 查询 | Mapper 条件查询 + service 统一传 owner/space | 与现有唯一索引一致，改动小 | 重构 FileService：范围过大 |
| init 事务 | 新增独立 `UploadInitCommitManager` `@Transactional` | 避免 S3 网络 IO 进入事务，明确回滚边界 | 在巨大 UploadService 上堆事务：不利测试 |
| 状态并发 | UploadSession 条件更新 CAS | 数据库原子认领，支持多实例 | JVM 锁：无法跨实例 |
| Archive 输入 | 配置上限 + bounded copy | 在临时盘写入期阻断 | 仅 metadata 预检：流读仍需硬上限 |

评估结论：方案符合现有 Spring/MyBatis-Plus/TypeScript 技术栈，不引入缓存、消息或新基础设施，不改变公开 API。

## 五、后端架构评审

- Controller：保持现有鉴权、路由和错误码；不接受客户端 S3 标识作为授权凭据。
- Service：FileService 负责 scope，UploadService 负责外部流程编排，`UploadInitCommitManager` 只负责 init 的 DB commit，状态迁移集中到 UploadSession Mapper/服务。
- Repository/Mapper：新增 scoped name 查询和 `transitionStatus` 条件更新；所有调用检查 affected rows。
- 事务边界：S3 init 在事务外；DB commit 覆盖 replacement `FOR UPDATE`、snapshot、node、session、chunks；S3 complete 在 CAS 成功后执行；DB finalize 独立短事务。
- 并发控制：UploadSession 是权威状态；node 状态只能作为同步/兼容字段，不能绕过 session CAS。
- 幂等：重复 merge/abort、重复永久删除均依赖状态/affected rows，只有首次成功者执行外部 side effect。

## 六、前端架构评审

```text
Web useUpload / Desktop upload-manager
    ↓
同一语义：fileMd5 = MD5(all bytes)
    ↓
既有 check/init/merge API
```

本次无页面、组件、状态管理或视觉改动。

## 七、数据库设计评审

- 不新增表、字段或索引作为目标方案。
- 复用现有 `file_node.owner_id/space_id/status/deleted`、`upload_session.status`、`file_chunk` 和 `file_version`。
- 需要确认 `FOR UPDATE`、batch insert 和条件状态更新均运行在实际数据库事务中。
- 若测试发现现有 schema 无法表达方案，必须停止当前 Phase 并先提交迁移设计确认；不得自动清理冲突数据。

## 八、缓存设计评审

本次不涉及缓存。现有可访问性缓存失效逻辑保持不变。

## 九、高并发设计评审

```text
预估压力：同一 uploadId 的 merge/abort 多请求并发；回收站父子节点重复输入
瓶颈：状态认领和 side effect 重复
解决方案：DB CAS + affectedRows；cleanup 只由获胜者执行；测试使用 CountDownLatch/20 并发
```

## 十、安全设计评审

- owner、tenant、space 校验保留在所有入口。
- 同名查询不得退化为裸 tenant+parent 查询。
- `storagePath`、`s3UploadId`、客户端 `fileMd5` 只作为请求数据，服务端仍以持久化 session 和既有权限校验为准。
- bounded copy 防止 Archive 输入流耗尽临时盘；超限临时文件必须清理。

## 十一、可扩展性评审

- `sampleHash` 若未来需要只能作为独立可选字段，本轮不引入。
- `transitionStatus` 可支持后续状态迁移，但本轮不扩展状态集合。
- Archive 输入上限采用配置属性，不固定部署环境。

## 十二、异常和容错设计

| 异常 | 处理方案 |
|---|---|
| DB init 失败 | Spring 回滚全部 DB 写入，调用 S3 abort best-effort，记录补偿失败 |
| S3 complete 失败 | session MERGING→FAILED；node 按既有恢复策略处理，不声称完成 |
| CAS 失败 | 返回冲突，不执行对应外部 side effect |
| 重复删除 | affected rows 为 0 时跳过 quota/ref/object side effect |
| Archive 超限/删除失败 | 超限抛 FILE_TOO_LARGE；删除失败记录日志并保证主异常不被静默吞掉 |

## 十三、架构风险分析

| 风险 | 影响等级 | 解决方案 |
|---|---|---|
| 既有调用点遗漏 scoped 查询 | High | 用全仓搜索和 SCOPE 测试覆盖所有计划调用点 |
| DB 事务代理未生效 | Critical | 独立 Bean、集成测试注入失败、验证 `FOR UPDATE` 同事务 |
| CAS 后 node/session 双写不一致 | High | session 先认领、finalize 短事务、并发测试核对最终状态 |
| 旧采样 MD5 存量 | Medium | 不自动迁移；发布说明标注，后续上传使用完整 MD5 |

## 十四、架构评审结论

```text
架构评分：通过（前提是按 Phase 逐步测试）
技术方案：复用现有服务/表/测试基础设施，新增最小事务管理与 CAS 能力
主要风险：实现遗漏调用点、事务代理/并发测试不足
优化建议：先 Phase 1 hash contract，再逐 Phase 进入后端状态/删除/Archive
是否进入开发：用户已在当前任务明确要求实施本轮定版方案，确认依据见 `confirmation.md`；允许进入 Phase 1
```
