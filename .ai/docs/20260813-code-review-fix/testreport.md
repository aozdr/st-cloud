# 测试报告：Code Review 修复迭代（20260813-code-review-fix）

## 测试范围
- 新增 `RelayUploadIntegrationTest`：TC-001~013（模式判定 / relayChunkSize 边界 / pacing / 攒批 uploadPart / 末片 / 权限 / 失败 abort / 超时清理 / 重复 seq 幂等 / 客户端自限速 / simpleUpload 限速 / file_chunk 状态落库）
- 回归：UploadStateMachine / ConcurrentUpload / 事件 Outbox / 文件对象 / 权限缓存 / 配额并发 / 收藏 / 搜索 / 同步消费 / 团队权限
- 标准校验：`rg "\?{3,}"` 无残留；迁移脚本幂等；前端构建

## 结果
| 模块 | 结果 |
|------|------|
| st-common | 全绿 |
| st-core（含新增 13 例 relay 测试） | 全绿 |
| st-sync | 全绿 |
| st-search | 全绿 |
| st-team | 全绿 |
| st-web `npm run build` | 通过 |
| st-desktop `tsc --noEmit` | 通过 |

## 缺陷记录
| 编号 | 问题 | 严重程度 | 状态 |
|------|------|---------|------|
| 1 | seq 幂等粒度错误：原实现按 8KB 读片段判重，导致同一请求后续字节被丢弃 | P0（测试发现） | 已修复（改为请求级 tryAcquireSeq） |
| 2 | UploadStateMachine 测试缺少 RelayBufferManager/SpeedLimitService 测试基建 | P1（测试基建） | 已修复 |

## 结论
全量回归通过，relay 核心路径（端点/幂等/权限/清理/限速）均有自动化覆盖，TEST_PASS 达成。
