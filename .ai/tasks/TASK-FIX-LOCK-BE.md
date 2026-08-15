# TASK-FIX-LOCK-BE（FileNodeVO 补锁定字段 — executor/implement，后端）

## 元信息

- Task ID: `TASK-FIX-LOCK-BE`
- taskCode: `LOCK-BE-01`
- etaMinutes: 20
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14

## 目标（定版）

1. `FileNodeVO` 新增 `lockedBy`（Long）、`lockedAt`（LocalDateTime）、`lockExpireAt`（LocalDateTime）字段（中文注释）。
2. `FileNode → FileNodeVO` 转换处填充锁字段：`FileServiceImpl` 的 VO 构建（`toVO`/convert）与团队文件列表（`GET /team/{spaceId}/files`，TeamServiceImpl 返回 FileNodeVO）均填充（FileNode 实体已有 lockedBy/lockedAt/lockExpireAt，直接 copy）。
3. 核对 `TeamServiceImpl.lockFile/unlockFile` 已写入 `locked_by/lock_expire_at`（23 号脚本列已存在）；若未写则补齐（lock：locked_by=当前用户、locked_at=now、lock_expire_at=now+hours（hours=0 永久）；unlock：置 NULL）。
4. 补测试：锁定后 VO 返回 lockedBy/lockExpireAt；解锁后为空。

## 范围

- include（写）：`st-core/src/main/java/com/stcloud/core/dto/FileNodeVO.java`、`FileServiceImpl.java`、`st-team/**/TeamServiceImpl.java`（团队列表 VO 填充 + lockFile 核对）、相关测试
- include（读）：`.ai/dispatch/**`
- exclude：前端、其它模块、创建子 Agent

## 验收标准

- VO 三字段下发；个人/团队列表均填充；lock/unlock 写列正确
- 验证由主线程统一串行执行（mvn 编译/测试）

## 验证

- 主线程串行跑 `mvn -q -pl st-core,st-team -am test`
