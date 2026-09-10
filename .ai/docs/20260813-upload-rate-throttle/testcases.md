# 测试用例

> 输出标准：`docs/newList/ai-test-case-standard.md`
> 关联需求文档：`.ai/docs/20260813-upload-rate-throttle/requirement.md`
> 落盘路径：`.ai/docs/20260813-upload-rate-throttle/testcases.md`

# 一、测试目标

验证：
- 低速率（rate<5MB）上传走中转，瞬时速率不超限速值
- 正常速率/不限速走 presigned 直传，行为不变
- 中转缓冲攒批 uploadPart 与末片处理正确
- 临时文件生命周期完整，无泄漏
- 中转接口权限与参数校验

# 二、测试范围

- 后端服务测试（UploadServiceImpl relay 逻辑 + RelayBufferManager + UploadPaceBucket）
- API 测试（init/relay-chunk/relay-finalize）
- 前端构建（st-web + st-desktop 类型与分支）
- 回归（直传模式不受影响）

# 三、测试环境

```
环境：H2 内存库（st-core 测试）+ Mock S3（StorageService mock）
数据库：H2
依赖服务：S3/MinIO（mock）
```

# 四、测试用例

### 用例 TC-001：不限速走直传（回归）

```
编号：TC-001
名称：rate=0 时 init 返回 direct 模式
类型：功能测试
优先级：P0
```
前置条件：用户无限速规则（rate=0）
测试步骤：
| 步骤 | 操作 | 预期结果 |
| 1 | POST /init（chunkSize=5MB） | transferMode=direct，relayChunkSize=null |
| 2 | 走现有 chunk-url/confirm/merge | 上传成功，行为与改动前一致 |
预期：direct 模式，presigned 直传不变

### 用例 TC-002：低速率判定中转模式

```
编号：TC-002
名称：rate<5MB 时 init 返回 relay 模式
类型：功能测试
优先级：P0
```
前置条件：用户上传限速 100KB/s（rateBytes=102400 < 5MB）
测试步骤：
| 步骤 | 操作 | 预期结果 |
| 1 | POST /init（chunkSize=5MB，clientLimit=null） | transferMode=relay |
| 2 | 检查 relayChunkSize | =max(8192, min(102400*2, 1MB))=204800 |
预期：relay 模式，relayChunkSize 计算正确

### 用例 TC-003：relayChunkSize 边界

```
编号：TC-003
名称：极低速率与高速率边界的小块大小
类型：边界测试
优先级：P1
```
| 输入 rate | 预期 relayChunkSize |
| 1KB/s(1024) | max(8192, min(2048,1MB))=8192（下限兜底） |
| 100KB/s(102400) | 204800 |
| 4MB/s(4194304) | min(8388608,1MB)=1MB（上限兜底） |
| 5MB/s(5242880) | 不触发 relay（rate>=chunkSize），走 direct |

### 用例 TC-004：中转上传瞬时速率不超限

```
编号：TC-004
名称：1KB/s 中转上传，接收速率受 pacing 限制
类型：性能测试
优先级：P0
```
前置条件：限速 1KB/s，上传 10KB 文件（2 个小块，relayChunkSize=8192）
测试步骤：
| 步骤 | 操作 | 预期结果 |
| 1 | init relay | 创建 RelaySession |
| 2 | POST relay-chunk seq=1（8192B） | acquireUploadPace 阻塞约 8s（8192/1024），接收期间速率≤1KB/s |
| 3 | POST relay-chunk seq=2（1808B） | 阻塞约 1.8s |
| 4 | POST relay-finalize | 末片 uploadPart + merge 成功 |
预期：每个小块接收耗时≈字节/速率，瞬时突发≤8192B

### 用例 TC-005：攒批 uploadPart（5MB 阈值）

```
编号：TC-005
名称：累积≥5MB 触发 uploadPart
类型：功能测试
优先级：P0
```
前置条件：rate=100KB/s（relayChunkSize=204800），文件 6MB
测试步骤：
| 步骤 | 操作 | 预期结果 |
| 1 | 连续 POST relay-chunk 至累积 5MB | 第 N 块触发 uploadPart(partNumber=1)，累积重置 |
| 2 | 继续至文件结束 | 余量 1MB < 5MB，暂不 uploadPart |
| 3 | relay-finalize | 末片 uploadPart(partNumber=2, 1MB) + merge |
预期：非末片≥5MB，末片<5MB，part 数=2

### 用例 TC-006：末片非 5MB 整数倍

```
编号：TC-006
名称：文件大小非 5MB 整数倍时末片处理
类型：边界测试
优先级：P0
```
| 文件大小 | 预期 part 数 | 末片大小 |
| 5MB 整 | 1 | 5MB（finalize 时余量0，不额外 uploadPart） |
| 7MB | 2 | 2MB |
| 3MB | 1 | 3MB（<5MB，仅末片） |

### 用例 TC-007：中转接口权限校验

```
编号：TC-007
名称：非 owner 访问 relay-chunk 被拒
类型：权限测试
优先级：P0
```
前置条件：用户B 访问用户A 创建的 uploadId
测试步骤：
| 步骤 | 操作 | 预期结果 |
| 1 | 用户B POST relay-chunk（A 的 uploadId） | 返回 PERMISSION_DENIED |
预期：非 owner/非租户管理员拒绝，不接收数据

### 用例 TC-008：临时文件清理

```
编号：TC-008
名称：finalize 后临时文件删除
类型：功能测试
优先级：P0
```
测试步骤：
| 步骤 | 操作 | 预期结果 |
| 1 | 完成中转上传 relay-finalize | 临时文件被删除 |
| 2 | 检查临时目录 | 无残留 |
预期：无泄漏

### 用例 TC-009：中转失败清理

```
编号：TC-009
名称：relay-finalize 失败时 abort + 清理
类型：异常测试
优先级：P0
```
前置条件：mock S3 completeMultipartUpload 抛异常
测试步骤：
| 步骤 | 操作 | 预期结果 |
| 1 | relay-finalize | 捕获异常，abort multipart，删临时文件 |
预期：无残留，S3 分片已 abort

### 用例 TC-010：超时清理

```
编号：TC-010
名称：uploadId 超时未活跃自动清理
类型：异常测试
优先级：P1
```
前置条件：创建 RelaySession 后不活跃超过超时阈值
测试步骤：
| 步骤 | 操作 | 预期结果 |
| 1 | 触发超时清理扫描 | session 被清理，临时文件删除，abort multipart |
预期：无泄漏

### 用例 TC-011：重复 seq 幂等

```
编号：TC-011
名称：客户端重试相同 seq 不重复写入
类型：功能测试
优先级：P1
```
测试步骤：
| 步骤 | 操作 | 预期结果 |
| 1 | POST relay-chunk seq=1 | 确认 |
| 2 | 再次 POST relay-chunk seq=1（重试） | 忽略重复，返回已确认，不重复追加 |
预期：幂等

### 用例 TC-012：客户端自限速中转

```
编号：TC-012
名称：客户端自限速 100KB/s（<5MB）走中转
类型：功能测试
优先级：P1
```
前置条件：服务端不限速，clientLimit=100
测试步骤：
| 步骤 | 操作 | 预期结果 |
| 1 | POST /init（clientLimit=100） | capRate(0,100)=100KB/s<5MB -> relay |
预期：自限速同样中转

# 五、测试覆盖要求

## 功能覆盖
- 正常流程：直传回归 + 中转完整流程
- 边界条件：relayChunkSize 边界、末片、5MB 整除
- 异常输入：权限拒绝、S3 失败、超时、重复 seq

## 权限覆盖
- 非 owner 拒绝
- 租户管理员允许

## 数据覆盖
- 小文件（<5MB，仅末片）
- 大文件（多 part）
- 非 5MB 整数倍

# 六、接口测试

### /init
```
请求: { fileName, fileSize, fileMd5, totalChunks, chunkSize, clientLimit? }
正常: { uploadId, s3UploadId, fileId, transferMode, relayChunkSize? }
```

### /relay-chunk
```
请求: params(uploadId, s3UploadId, seq) + body(octet-stream)
正常: { confirmed:true, partUploaded:bool, partNumber:int }
异常: 403(PERMISSION_DENIED), 400(seq 非法)
```

### /relay-finalize
```
请求: params(uploadId, s3UploadId)
正常: FileNodeVO
异常: 403, 500(merge 失败)
```

# 七、自动化测试建议

```
是否适合自动化：是（后端 H2 集成测试）
推荐工具：JUnit5 + Mockito（mock StorageService）
测试位置：st-core/src/test/.../RelayUploadIntegrationTest.java
覆盖范围：TC-001~006, TC-008~011
前端：npm run build 类型检查
```

# 八、缺陷记录

| 编号 | 问题 | 严重程度 | 状态 |
|------|------|---------|------|
| - | - | - | - |

# 九、测试总结

```
测试数量：12
通过数量：待执行
失败数量：待执行
风险：simpleUpload 限速未纳入（已知限制，后续扩展）
建议：MVP 验证 multipart relay 核心路径
```
