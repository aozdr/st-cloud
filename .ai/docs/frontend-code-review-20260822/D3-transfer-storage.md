# D3 传输与本地存储 Code Review

> 审查人：主线程 reviewer（ox-alpha）/ 日期：2026-08-22 / 方式：静态人工审查（upload-manager/download-manager/api-client/md5/file-utils/database 关键区全文精读；db-migrate/transfer-settings 仅结构级核对）
> 范围：`st-desktop/src/upload-manager.ts`、`download-manager.ts`、`database.ts`、`api-client.ts`、`utils/md5|file-utils|block-hash.ts` 等

## 模块概述

- 上传：采样 MD5 秒传检查 → init/merge 分片直传 S3（并发 5）/ 低速中转模式；支持暂停恢复（服务端已传分片查询）
- 下载：`.part` 临时文件 + HTTP Range 断点续传 + 字节达标才判成功
- 存储：sql.js 内存库，每次变更 `persist()` 全量导出写盘

## 问题清单

| 编号 | 严重度 | 位置 | 问题 | 证据(代码片段) | 建议 |
|---|---|---|---|---|---|
| D3-1 | P1 | database.ts:216-220 + upload-manager.ts:354-358 | **每次 DB 变更都全量导出写盘**：上传每完成一个分片调用 updateTask→persist()，即 `db.export()` 整库序列化+同步写文件；分片越多开销 O(n²)（uploadedChunks JSON 也随进度增长），多任务并发时阻塞主进程明显 | `const data = db.export(); fs.writeFileSync(dbPath, Buffer.from(data));` | 改防抖合并持久化（如 500ms 窗口）+ 退出/低频兜底；进度类字段降频落库 |
| D3-2 | P1(待确认) | api-client.ts:33 ↔ st-web/src/lib/api.ts:93 | 双端刷新令牌响应字段不一致：web 取 `data.token` 且更新 refreshToken 对；desktop 取 `data.accessToken` 且**从不更新** refreshTokenValue。两处必有一处与后端不符，desktop 侧刷新可能静默失败 | `const newToken = res.data?.data?.accessToken;` | 与后端 Result 契约对齐；刷新后同步回渲染进程 |
| D3-3 | P1 | utils/md5.ts:21-55 ↔ st-web/hooks/useUpload.tsx:28-49 | 双端「采样 MD5」算法不同：desktop=首2M+中2M+尾2M；web=首2M+文件大小(float64)。同一大文件两端指纹不同 → 秒传跨端永不命中（功能失效）；且两者均为非全量指纹 | 两套采样实现并存 | 统一为全量流式 MD5（calculateFileMd5 已存在）；至少双端算法一致并追加长度域 |
| D3-4 | P1 | upload-manager.ts:77,86-98 | 秒传判定使用采样 MD5（同 W2-1 弱指纹家族）：首中尾共 6MB 相同的不同文件会被误秒传，用户静默拿到错误内容 | `const md5 = await calculateSampledMd5(...)` 后直接 `/file/upload/check` | 与 D3-3 一并改全量哈希；或服务端对命中样本二次校验 |
| D3-5 | P2 | upload-manager.ts:283-295 | chunk-url 申请 `while (!url)` 无最大尝试次数，服务端持续返回空 URL 时空转 | 同 W2-3 | 加上限与退避 |
| D3-6 | P2 | download-manager.ts:168-172 | 下载完成仅按字节数判定，未与服务端 fileMd5 比对完整性 | `if (totalBytes >= task.fileSize)` | 完成后校验 MD5 再 finalize |
| D3-7 | P2 | upload-manager.ts:305-325 | 在途分片的 fetch 无 AbortSignal，暂停/取消要等当前 5MB 分片发完才生效（最坏数分钟） | 无 signal 传递 | 传 AbortController.signal，pause/cancel 即刻中断 |
| D3-8 | P2 | download-manager.ts:85 | 流式下载 URL 由 baseURL 直接拼接 nodeId，未编码校验（nodeId 来自渲染进程参数） | `` `${baseURL}/file/${task.nodeId}/stream` `` | encodeURIComponent + ID 格式校验 |

## 亮点

- 下载链路工程质量高：`.part` 临时文件、Range 续传、200/206 双分支处理（服务端忽略 Range 时覆盖重写防超容量）、超发截断、字节达标才算完成、跨盘符 EXDEV 降级复制——边界考虑完整
- 取消上传会调用服务端 abort 清理 S3 multipart，不留孤儿分片
- 启动恢复策略克制：未完成任务仅标记 paused 等用户手动恢复，避免开机自动风暴
- speed 瞬时值走内存缓存不入库，读写分离意识正确
- block-hash 覆盖写入先删后插保持幂等，且有配套测试

## 结论

传输两端的协议实现（分片/续传/取消清理）扎实，短板在**存储层的持久化策略（D3-1）**与**双端一致性（D3-2/D3-3）**：前者随数据量增长会成为全局性能瓶颈，后者让「秒传」这一核心特性在跨端场景下既失效又有误判风险。建议与 W2-1 合并为一个专项修复。

统计：P0×0　P1×4（含 1 项待确认）　P2×4
