# 测试用例：Code Review 修复迭代

## 后端集成测试

| TC | 场景 | 验证点 | 对应 |
|----|------|--------|------|
| TC-C1-1 | 同租户同 md5 已 deleted 行存在时 acquire | 复活行返回，deleted=0/status=0/ref_count=1，storage_path 更新 | C1 |
| TC-C1-2 | 永久删除后同 md5 重传 simpleUpload | 不抛 NPE，返回新节点且复用对象 | C1 |
| TC-M1-1 | 非 owner 非管理员调用 mergeChunks | PERMISSION_DENIED | M1 |
| TC-M2-1 | 配额不足时 merge | 配额预检拒绝，S3 未 complete | M2 |
| TC-M2-2 | completeMultipart 抛错（替换上传） | abort 调用，旧版本恢复，事务回滚 | M2 |
| TC-M3-1 | relayFinalize 成功合并 | 事务代理生效（Mock 校验 claimMerging 在事务内），节点 COMPLETED | M3 |
| TC-M6-1 | 并发多用户上传不串行 | 正常路径无 FOR UPDATE（Mock 验证调用非锁定方法），总容量不超限 | M6 |
| TC-M5-1 | block-upload 无会话 | 拒绝并 abort | M5 |
| TC-M5-2 | 会话存在但 storagePath 被篡改 | 以服务端会话为准，delete 目标为会话路径 | M5 |
| TC-M5-3 | 非 owner 用他人 fileNodeId + 自己会话 | 权限拒绝 | M5 |
| TC-M7-1 | block-abort 清理会话并 abort S3 | abortMultipart 调用，会话移除 | M7 |
| TC-M8-1 | block-check 返回可复用/缺失块 | 布局对比正确 | M8 |
| TC-M8-2 | block-upload 全流程（含去重命中/未命中、版本+1、块布局写入、事件） | 节点/对象/块表/事件正确 | M8 |
| TC-M8-3 | block-upload 并发重复调用 | 仅一次 complete/扣配额（并发守卫） | M8/M2 |
| TC-M8-4 | 配额不足 block-upload | 事务回滚 + S3 abort | M8/M2 |
| TC-M4-1 | 28 号脚本重复执行 | compare-schema PASS / 无 duplicate column | M4 |

## 前端验证

| TC | 场景 | 验证点 |
|----|------|--------|
| TC-FE1-1 | Web relay 上传中点击 X | 任务移除，abort API 被调用 |
| TC-FE1-2 | relay finalize 阶段取消 | 等待返回后 abort，不落库 |
| TC-FE2-1 | 本地存在且无 sync_state 的对账 | 本地保留，无下载覆盖 |
| TC-FE2-2 | 有 state 且 md5 不一致 + 本地未修改 | 维持云端更新（不回归） |

## 回归

- 既有 RelayUploadIntegrationTest（14 例）、UploadStateMachineIntegrationTest、EventOutbox、QuotaConcurrency、FileObjectIntegrationTest 全绿。
- `mvn test` 全模块；`st-web npm run build`；`st-desktop tsc --noEmit`；`compare-schema.ps1` PASS；`verify-loop.ps1` PASS。
