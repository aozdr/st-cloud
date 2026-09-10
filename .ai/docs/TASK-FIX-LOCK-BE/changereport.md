# Change Report：FileNodeVO 补锁定字段并填充（LOCK-BE-01）

- Task: `TASK-FIX-LOCK-BE`
- Dispatch: `lockbe-01`
- Agent: executor（taskType=implement）
- 日期: 2026-08-14

## 背景

P2 文件锁定功能已在实体与库表（23 号脚本）落地，但 `FileNodeVO` 未下发 `lockedBy/lockedAt/lockExpireAt`，前端无法展示/判断文件锁定状态。本任务为已定版小型后端实现任务，按 TASK 文件范围执行，验证（mvn 编译/测试）由主线程串行统一执行。

## 修改文件清单

- `st-core/src/main/java/com/stcloud/core/dto/FileNodeVO.java`：新增 `lockedBy`（Long）、`lockedAt`（LocalDateTime）、`lockExpireAt`（LocalDateTime）三个字段，均带中文注释与 `@JsonFormat` 时间格式。
- `st-core/src/main/java/com/stcloud/core/service/impl/FileServiceImpl.java`：`toVO(FileNode)` 中直接透传实体的 `lockedBy/lockedAt/lockExpireAt`；个人列表/详情、团队列表/详情、搜索、路径解析等所有经 `toVO` 的 VO 下发路径统一覆盖。
- `st-team/src/main/java/com/stcloud/team/service/impl/TeamServiceImpl.java`：`lockFile`/`unlockFile` 由 `updateById` 改为 `LambdaUpdateWrapper` 显式 set 三列，修复 MyBatis-Plus 默认更新策略不写 NULL 导致的「解锁不落库 / 永久锁残留旧过期时间」（主线程验收时由新增测试暴露并修复）。
- `st-team/src/test/java/com/stcloud/team/AbstractTeamIntegrationTest.java`：注入 `FileService` 供锁定 VO 集成测试使用。
- `st-team/src/test/java/com/stcloud/team/service/TeamServiceIntegrationTest.java`：新增 `lockFile_voReturnsLockFieldsAndUnlockClearsThem`，覆盖锁定 24 小时、永久锁（hours=0）、解锁三个状态下的 VO 字段。
- `st-core/src/test/java/com/stcloud/core/service/impl/FileServiceFlowIntegrationTest.java`：新增 `vo_returnsLockFieldsForLockedNode`，覆盖个人文件详情与个人分页列表的锁定字段下发。
- `st-team/src/test/java/com/stcloud/team/TeamTestApplication.java`：注册真实 `FileServiceImpl` Bean（`getTeamNodeById` 仅依赖 `FileNodeMapper` 与内存缓存），并对 `ReliableEventPublisher`/`FileObjectService` 补 Mock，恢复测试上下文。

## 验收修复记录：lockFile/unlockFile 写列（主线程验证发现并修复）

初版子代理核对结论为「写列已符合定版要求，无需修改」；主线程串行跑 `mvn -q -pl st-core,st-team -am test` 时，新增用例 `lockFile_voReturnsLockFieldsAndUnlockClearsThem` 在「解锁后再次永久锁定」步骤抛 `文件已被锁定`，暴露真实 Bug：

- 根因：MyBatis-Plus `updateById` 默认 `NOT_NULL` 字段策略，实体属性置 null 时**不会生成对应 SET 语句**。因此 `unlockFile` 的「三列置 NULL」从未真正落库，解锁后 DB 仍保留锁定状态（前端始终显示锁定、他人无法重新锁定）；`lockFile(hours=0)` 也不会清掉旧的 `lock_expire_at`，永久锁会残留过期时间。
- 修复：两个方法改为 `fileNodeMapper.update(null, new LambdaUpdateWrapper<FileNode>()...set(...))` 显式 set 三列（含 NULL 值），保证锁定/解锁状态与 DB 严格一致。

## 与验收标准对照

| 验收标准 | 结果 |
|----------|------|
| VO 三字段下发 | 通过（`FileNodeVO` 新增三字段，`toVO` 统一填充） |
| 个人/团队列表均填充 | 通过（个人与团队列表均走 `FileServiceImpl.toVO`，含 `listTeamFiles`/`listDirectory`/详情/搜索） |
| lock/unlock 写列正确 | 通过（验收时发现 updateById 不写 NULL 的 Bug，已修复为显式 set；新增用例全绿） |
| 补测试 | 通过（st-core 个人路径 + st-team 团队 lock/unlock 路径，共 2 个新用例） |
| 验证 | 通过（主线程串行 `mvn -q -pl st-core,st-team,st-share -am test` 全绿） |

## 测试结果

- 子 Agent 静态自检通过（只产出代码与测试，编译/测试由主线程串行执行）。
- 主线程串行执行 `mvn -q -pl st-core,st-team,st-share -am test`：全绿（st-auth / st-core / st-team / st-share）。
- 前端 `npx tsc --noEmit -p tsconfig.app.json` 与 `npm run build`：全通过。

## 风险与说明

- `FavoriteServiceImpl.toVO` 也有独立的 FileNode→FileNodeVO 转换（收藏列表），不在本 TASK 修改范围（exclude：其它模块），当前收藏列表不会下发锁定字段；如后续需要，建议另立 TASK 补齐。
- 无接口契约破坏：VO 仅新增字段，向后兼容。
- 无数据库变更、无迁移脚本需求（列已由 23 号脚本提供，H2 schema 已含三列）。
- 解锁写库缺陷属既有 P2 锁定功能隐藏 Bug，本次一并修复；`checkNotLocked` 依赖 DB 锁定列，修复后其行为（含过期判定）与真实状态一致。

## State Delta

- 新增 artifacts：`.ai/docs/TASK-FIX-LOCK-BE/changereport.md`。
- 更新 artifacts：`FileNodeVO.java`、`FileServiceImpl.java`、`TeamServiceImpl.java`（解锁/重锁显式 set）、`TeamTestApplication.java`、`TeamServiceIntegrationTest.java`、`FileServiceFlowIntegrationTest.java`、`AbstractTeamIntegrationTest.java`。
- 主线程验证：后端全模块测试 + 前端 tsc/build 全绿。

## 下一步

- 已由主线程完成 CODE_REVIEW 复核（VO 命名/类型、toVO 统一填充、解锁写库修复、测试覆盖、无越界改动），进入 TEST_PASS/ACCEPT。
- 可选后续：为收藏列表（FavoriteServiceImpl）同步锁定字段（另立 TASK）。
