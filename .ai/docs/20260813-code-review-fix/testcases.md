# 测试用例：Code Review 修复迭代

## 测试范围
- 后端集成测试：`RelayUploadIntegrationTest`（TC-001~011）
- 回归：UploadStateMachine / ConcurrentUpload / 事件 Outbox / 权限缓存 / 配额并发 / 文件对象既有用例
- 标准校验：`rg "\?{3,}"` 无残留；迁移脚本幂等逻辑；前端构建
- 遗留补齐：TC-012 客户端自限速中转、TC-013 simpleUpload 限速（F6）

## 用例清单
| ID | 前置 | 步骤 | 预期 |
|----|------|------|------|
| TC-001 | 无 | init(rate=0) | transferMode=direct |
| TC-002 | 无 | init(0<rate<5MB) | transferMode=relay + relayChunkSize 非空 |
| TC-003 | relay | 校验 relayChunkSize | = clamp(rate*2, 8192, 1MB) |
| TC-004 | relay | 顺序 POST 小块 | 瞬时速率 ≤ 限速（首个突发 ≤ relayChunkSize 容忍） |
| TC-005 | relay | 累积 ≥ 5MB | 触发 uploadPart（partUploaded=true） |
| TC-006 | relay | finalize | 末片上传 + merge 成功，临时文件删除 |
| TC-007 | relay | 非 owner 调 relay-finalize | PERMISSION_DENIED |
| TC-008 | relay | 非 owner 调 relay-chunk | PERMISSION_DENIED |
| TC-009 | relay | finalize 失败 | S3 abort + 临时文件清理 |
| TC-010 | relay | 会话超时 | 定时清理触发 abort S3 |
| TC-011 | relay | 重复 seq | 幂等跳过，字节不重复 |
| TC-012 | 服务端不限速 | init(clientLimit=100) | capRate(0,100)=100KB/s<5MB → relay，走中转 |
| TC-013 | 限速 1KB/s | simpleUpload 5MB 文件（mock StorageService） | acquireUploadPace 被调用，接收耗时 ≈ 字节/速率 |

## 覆盖要求
- 模式判定与阈值边界（0 / 临界 / 5MB 上下）
- 权限两入口（chunk / finalize）
- 失败与超时路径的 S3 abort 断言
- 重复请求幂等断言（字节数不变）
- 客户端自限速与 simpleUpload 限速断言（遗留 F6/TC-012）
