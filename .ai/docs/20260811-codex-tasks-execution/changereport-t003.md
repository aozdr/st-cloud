# Change Report：TASK-003 容量并发控制优化

> 编码完成后的变更汇总。归属 exitCriteria：IMPLEMENTED。产出者：backend-engineer。关联 State：`.ai/state/20260811-codex-tasks-execution.yaml`。

## 背景
原配额校验为「读 used -> 校验 quota -> 更新 used」三步，中间存在竞态窗口：并发上传同时通过校验后各自累加，导致配额超卖；云盘总容量检查同样是读后判，无并发保护。TASK-003 将校验与扣减合并为一次原子条件更新，从根上消除竞态。

## 修改文件清单
**修改**
- `st-core/.../mapper/UserQuotaMapper.java` — `updateStorageUsed` 改为原子条件扣减：`SET used=used+? WHERE used+?>=0 AND (quota IS NULL OR used+?<=quota)`
- `st-core/.../mapper/TeamStorageMapper.java` — `updateTeamStorageUsed` 同样原子化（quota 上限 + 非负双守卫）
- `st-core/.../mapper/CloudCapacityMapper.java` — 新增 `getCloudTotalCapacityForUpdate`（`SELECT ... FOR UPDATE` 行锁）
- `st-core/.../service/impl/CloudStorageServiceImpl.java` — `checkCapacity` 改用行锁读总容量，使已配置总容量的租户并发校验串行化
- `st-core/.../service/impl/upload/UploadManager.java` — 新增 `consumeQuota(userId, spaceId, delta)`：原子扣减，正向返回 0 行抛 `STORAGE_QUOTA_EXCEEDED`
- `st-core/.../service/impl/UploadServiceImpl.java` — 秒传/简单上传/合并三处扣减改走 `consumeQuota`（同时删除不再使用的 quota mapper 字段）
- `st-core/.../service/impl/FileServiceImpl.java` — 复制文件（个人/团队）扣减返回 0 行即抛配额异常，回滚本次复制
- `st-core/.../service/impl/VersionServiceImpl.java` — 版本恢复差值扣减原子化：delta>0 且 0 行抛异常，delta<0 释放忽略
- `st-core/src/test/.../QuotaConcurrencyIntegrationTest.java` — 10 线程并发竞争测试
- `st-core/src/test/.../UploadStateMachineIntegrationTest.java` — setUp 补测试用户行（配额 NULL 不限）适配原子扣减

## 与 TASK 验收标准对照
| 验收标准 | 实现 | 状态 |
|---|---|---|
| 多线程并发上传超配额：仅合法请求成功，其余抛 STORAGE_QUOTA_EXCEEDED | 原子条件 UPDATE + `consumeQuota` 0 行抛异常；并发测试 10 线程 quota=5000/请求 1000 恰 5 成功 5 拒绝 | ✅ |
| used 永不为负 | SQL 保留 `storage_used + delta >= 0` 守卫；释放路径 0 行静默 | ✅ |
| 替换上传差值正确 | 合并/版本恢复按 delta（可正可负）原子调整，正向不足抛异常 | ✅ |

## 测试结果
- `mvn test -pl st-core -am`：退出码 0，22 个测试全绿
- 新增 `QuotaConcurrencyIntegrationTest`：10 线程并发 `consumeQuota`，断言 `storage_used==5000` 精确、5 成功/5 拒绝/0 意外异常
- TASK-001/002 测试回归通过；全模块 `mvn compile` 通过；`.ai/scripts/verify-loop.ps1` PASS

## 明确未改动项（符合 TASK 禁止范围）
- 配额表结构未变（继续用 sys_user/team_space 的 storage_used/storage_quota）
- 上传接口契约未变

## 风险
- **云盘总容量行锁**：仅对已配置 `cloud_total_capacity` 的租户生效（未配置 NULL 时零开销）；已配置租户的并发上传按租户串行化（正确性换取吞吐，属显式管理上限场景）
- **预检查保留**：上传入口仍保留读型 `checkQuotaForUpload` 作快速失败（UX 早期提示），**权威判定为原子扣减**（不再存在「读后写」竞态）；文档已说明其非权威性
- 释放路径（回收站/版本缩小）0 行视为数据异常静默忽略，不抛错（避免误拦释放）