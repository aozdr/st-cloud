# TASK：TASK-06 simpleUpload 限速接入（F6 遗留）

> 开发前置产物。编码输入只接受本文件。
> 关联 State: `.ai/state/20260813-code-review-fix.yaml`
> 关联文档: `.ai/docs/20260813-code-review-fix/requirement.md` F6 / `design.md`

## 元信息
- Task ID: `TASK-20260813-code-review-fix-06`
- 归属 Agent: backend-engineer

## 目标
修复 upload-rate-throttle 遗留 F6：simpleUpload（<100MB 小文件，服务端 putObject 中转）完全未接限速，小文件可绕过限速。要求 simpleUpload 接入限速节流，不再绕过。

## 修改范围
- `st-core/src/main/java/com/stcloud/core/service/impl/UploadServiceImpl.java`：`simpleUpload` 解析有效限速（`capRate(服务端限速, clientLimit)`）；`rate > 0` 时对 `file.getInputStream()` 做限速包装（每 8KB 步进调用 `userTransferLimiter.acquireUploadPace` 阻塞 pacing，或新增 `ThrottledInputStream` 工具类），再交给 `storageManager.uploadObject`；`rate=0` 行为完全不变。
- 若新增工具类，放在 `st-common` 或 `st-core` 合适包并加中文注释。

## 禁止修改范围
- 不改 DownloadService / DownloadBucket
- 不改 relay 中转路径与 relayChunkSize 语义
- 不改 simpleUpload 的秒传/去重/配额逻辑
- 不改数据库

## 验收标准
- [ ] 限速 1KB/s 时 simpleUpload 5MB 文件，服务端接收速率 ≤ 限速（pacing 生效）
- [ ] rate=0 或未限速时 simpleUpload 行为与耗时不变（零开销）
- [ ] 已存在的秒传（checkInstantUpload）路径不受影响

## 测试要求
- 由 TASK-04 TC-013 覆盖（mock StorageService，验证 acquireUploadPace 被调用且耗时符合限速）
- `mvn compile -pl st-core,st-common -am` 通过
