# TASK：后端上传低速率中转限速

> 开发前置产物。编码输入只接受本文件。

## 元信息

- Task ID: `TASK-007`
- 关联任务 State: `.ai/state/20260813-upload-rate-throttle.yaml`
- 关联文档: `.ai/docs/20260813-upload-rate-throttle/design.md` / `testcases.md`
- 归属 Agent: backend-engineer
- 创建者: workflow-manager
- 日期: 2026-08-13

## 目标

实现上传低速率中转：当有效限速 < 分片下限(5MB) 时，上传改走服务端中转路径，服务端逐块 pacing 接收并缓冲，攒够 5MB uploadPart，末片无下限，最终 merge。实现瞬时速率限速。

## 修改范围

- `st-common/src/main/java/com/stcloud/common/ratelimit/UserTransferLimiter.java`：新增 UploadPaceBucket + acquireUploadPace
- `st-core/src/main/java/com/stcloud/core/service/StorageService.java`：接口新增 uploadPart
- `st-core/src/main/java/com/stcloud/core/service/impl/StorageServiceImpl.java`：实现 uploadPart
- `st-core/src/main/java/com/stcloud/core/service/impl/upload/UploadStorageManager.java`：暴露 uploadPart
- `st-core/src/main/java/com/stcloud/core/config/UploadRelayConfig.java`：新增（临时目录+超时配置）
- `st-core/src/main/java/com/stcloud/core/service/impl/upload/RelayBufferManager.java`：新增（缓冲管理器）
- `st-core/src/main/java/com/stcloud/core/service/impl/UploadServiceImpl.java`：init 模式判定 + relayChunk + relayFinalize
- `st-core/src/main/java/com/stcloud/core/controller/FileController.java`：新增 /relay-chunk + /relay-finalize 端点
- `st-core/src/main/java/com/stcloud/core/dto/UploadInitResponse.java`：新增 transferMode + relayChunkSize

## 禁止修改范围

- DownloadServiceImpl / UserTransferLimiter.DownloadBucket（下载已达标）
- SpeedLimitController / SpeedLimitManageServiceImpl / SpeedLimitCache / SysRateLimit（限速管理不变）
- ShareController / ShareServiceImpl（分享链路独立问题）
- UploadManager（claimMerging/consumeQuota 内部不改，仅调用）
- 数据库迁移脚本（无 DB 变更）
- st-web / st-desktop（属 TASK-008）

## 验收标准

- [ ] rate=0 或 rate>=5MB：init 返回 direct，行为不变
- [ ] 0<rate<5MB：init 返回 relay + relayChunkSize
- [ ] relayChunkSize=max(8192, min(rate*2, 1MB))
- [ ] relay-chunk pacing 接收，瞬时速率<=限速
- [ ] 累积>=5MB 触发 uploadPart，末片<5MB
- [ ] relay-finalize 末片+merge 成功，临时文件删除
- [ ] 非 owner 访问 relay-chunk 返回 PERMISSION_DENIED
- [ ] relay-finalize 失败时 abort+清理
- [ ] 重复 seq 幂等
- [ ] H2 测试全绿 + mvn compile 通过

## 测试要求

- 新增 RelayUploadIntegrationTest（覆盖 TC-001~006, TC-008~011）
- mvn compile -pl st-core,st-common -am 通过
- 现有 UploadStateMachineIntegrationTest 不回归

## 输出要求

编码完成后输出 Change Report 落盘 `.ai/docs/20260813-upload-rate-throttle/changereport.md`。
