# Code Review 记录：Code Review 修复迭代（20260813-code-review-fix）

## 范围
本迭代全部改动（后端 relay 修复 + 前端体验 + 注释/迁移修复 + 测试）。

## 检查维度与发现

### 结构
- FileController 新增端点与既有上传接口权限注解一致；UploadServiceImpl 保持门面编排，RelayBufferManager 职责单一。
- 请求级 seq 原子认领（`tryAcquireSeq`）解决并发重复写入；Content-Length 校验前置。

### 正确性
- 测试捕获 1 个 P0：seq 幂等误放到 8KB 读片段粒度，导致同一请求后续字节被丢弃 → 已改为请求级认领（TC-011 覆盖）。
- relayFinalize 失败 abort 与 mergeChunks 的 handleMergeFailure 叠加：S3 abort 幂等，重复 abort 已容错。

### 性能
- pacing 阻塞式接收仅低速率触发；定时清理 60s 扫描，成本可控。

### 风格
- 乱码注释清零（rg 验证）；新增注释为中文；UploadPaceBucket 与 DownloadBucket 的同构重复为既有味道，本迭代未扩大。

## 问题清单
| 编号 | 问题 | 级别 | 状态 |
|------|------|------|------|
| CR-1 | seq 幂等粒度缺陷 | P0 | 已修复（TC-011） |
| CR-2 | UploadStateMachine 测试基建缺口 | P1 | 已修复 |
| CR-3 | mergeChunks/abortUpload 无 owner 校验（既有） | P1 | 遗留，建议后续迭代 |

## 结论
本迭代改动通过 Code Review（遗留项不阻塞本迭代，已记录）。
