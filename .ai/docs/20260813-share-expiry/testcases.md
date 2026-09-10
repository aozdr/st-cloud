# 测试用例：文件分享可选过期时间（20260813-share-expiry）

## 测试范围

- st-share 后端：过期时间创建/更新/清除/访问控制全链路（H2 集成测试，真实 Mapper + Mock 外部服务）。
- st-web 前端：构建通过（tsc + vite build）；过期状态展示逻辑由代码评审确认。

## 集成测试用例（ShareServiceImplExpiryIntegrationTest）

| 编号 | 用例 | 前置 | 步骤 | 预期 |
|------|------|------|------|------|
| S1 | 创建有限期分享 | 用户 U1、文件 F1 | `createShare({fileNodeId:F1, expireAt: 未来})` | 返回 VO，`expireAt` 等于入参；DB 行 `expire_at` 非空 |
| S2 | 创建永久分享 | 同上 | `createShare({fileNodeId:F1, expireAt: null})` | VO/DB `expireAt` 为 null |
| S3 | 创建过去时间分享 | 同上 | `createShare({expireAt: 过去})` | 抛 `BusinessException`，code=BAD_REQUEST，提示"过期时间必须晚于当前时间" |
| S4 | 未过期访问成功 | S1 结果 | `accessShare(shareCode)` | 返回 `ShareAccessVO`，文件信息正确 |
| S5 | 过期后访问被拒 | 建分享后把 `expire_at` 改为过去 | `accessShare(shareCode)` | 抛 `BusinessException`，code=3002 SHARE_EXPIRED |
| S6 | 过期后下载被拒 | 同上 | `getDownloadUrl(shareCode,...)` | 抛 SHARE_EXPIRED |
| S7 | 过期后目录列表被拒 | 同上（文件夹分享） | `listShareFiles(shareCode,...)` | 抛 SHARE_EXPIRED |
| S8 | 过期后流式预览被拒 | 同上 | `streamShareFile(shareCode,...)` | 抛 SHARE_EXPIRED |
| S9 | 更新修改过期时间 | S1 结果 | `updateShare(id, {expireAt: 新未来时间})` | 更新成功；新时间点前可访问、之后被拒 |
| S10 | 更新清除过期 | S1 结果 | `updateShare(id, {clearExpireAt: true})` | 更新成功；DB `expire_at` 为 null；此后访问成功 |
| S11 | 更新传过去时间 | S1 结果 | `updateShare(id, {expireAt: 过去})` | 抛 BAD_REQUEST"过期时间必须晚于当前时间" |
| S12 | 列表返回过期时间 | 创建有限期/永久各一个 | `listShares(...)` | VO 中有限期分享 `expireAt` 非空、永久分享为 null |
| S13 | 私密分享过期校验优先于提取码 | 过期私密分享 | `accessShare(shareCode, 错误密码)` | 抛 SHARE_EXPIRED（而非密码错误） |
| S14 | clearExpireAt=false 不清除 | S1 结果 | `updateShare(id, {clearExpireAt: false})` | `expire_at` 不变 |

> 说明：`expire_at` 改为过去时间通过直接操作 `fileShareMapper`（真实 SQL UPDATE）模拟，避免等待真实时间流逝。

## 单元/契约测试

| 编号 | 用例 | 预期 |
|------|------|------|
| U1 | `LocalDateTime` 可解析前端格式 `2026-08-20T23:59:59`（无 Z） | 解析成功 |
| U2 | 前端旧格式（带 Z）不作为依赖 | 记录行为，不阻塞（格式契约以新格式为准） |

## 前端验证

| 编号 | 验证点 | 预期 |
|------|--------|------|
| F1 | `ShareDialog` 有效期选项渲染与提交格式 | 提交 `expireAt` 为 `yyyy-MM-ddTHH:mm:ss`（无 Z）；无限期不提交 |
| F2 | `ShareManagePage` 已过期展示 | `status=1` 且 `expireAt` 早于当前 → 展示"已过期" |
| F3 | 构建 | `npm run build` 通过 |

## 回归范围

- `mvn test` 全模块（含既有 st-core/st-team/st-search 测试）。
- 分享创建、取消、访问、下载等既有行为不回归（S1/S2/S4/S12 覆盖）。
