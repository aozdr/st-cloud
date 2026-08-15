# TASK-SEC-BATCH-2（S-06 分享码 / S-07 原子计数 / S-09 流式限速 — executor/implement）

## 元信息

- Task ID: `TASK-SEC-BATCH-2`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: security-recheck S-06/S-07/S-09（用户定版：分享码 4 位数字字母；流式默认 5MB/s）

## 目标

三项安全加固（均在 st-share）：

### S-06 分享码改为 4 位数字字母 + 冲突重试

1. `ShareServiceImpl.generateShareCode` 改用 `SecureRandom` 生成 **4 位**：字符集 `0-9 + A-Z`（大写字母，排除 `0/O/1/I` 易混字符 → `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`，32 字符集）。
2. **冲突重试**：生成后查询 `fileShareMapper` 是否已存在该 `share_code`（`uk_share_code` 唯一），存在则重新生成，最多重试 8 次；仍冲突抛业务异常（避免 DuplicateKeyException 500）。
3. 现有历史分享码（8 位 hex）不变，仅新生成生效；`share_code` 列 VARCHAR(32) 无需变更。
4. 更新测试：新分享 shareCode 长度为 4 且字符集合规；冲突重试逻辑（mock 已存在则重生成）。

### S-07 下载次数原子条件更新（消除 TOCTOU）

1. `getDownloadUrl` 与 `streamShareFile` 的"检查 downloadCount >= downloadLimit → 递增"改为**原子条件更新**：
   ```java
   int updated = fileShareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
           .eq(FileShare::getId, share.getId())
           .and(w -> w.isNull(FileShare::getDownloadLimit)
                   .or().apply("download_count < download_limit"))
           .setSql("download_count = download_count + 1"));
   if (updated == 0) {
       throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "下载次数已达上限");
   }
   ```
2. 移除前置"查询判断"（保留快速失败可选），并发下由数据库原子保证不超限。
3. 更新既有限次测试断言。

### S-09 流式传输默认限速 5MB/s

1. `ShareServiceImpl` 增加常量 `STREAM_RATE_BYTES_PER_SEC = 5 * 1024 * 1024L`。
2. `streamShareFile` 的读块循环按字节 pacing：根据已写字节数计算目标耗时，超速则 `Thread.sleep`（参考 DownloadServiceImpl.pacedTransfer 风格，中文注释）。
3. 测试：验证限速路径存在（如 1MB 数据总耗时 ≥ 理论下限的宽松断言，或 mock 校验 pacing 计算），避免测试过慢。

## 范围

- include：`st-share/**`（ShareServiceImpl + 测试）
- exclude：`st-web`、其它 `st-*` 模块主代码、`docker/mysql/init`、创建子 Agent

## 验收标准

- 新分享 shareCode 4 位且字符集合规；冲突重试生效（测试覆盖）
- 下载计数原子条件更新（rg 复核无"先查后增"）；并发不超限
- streamShareFile 限速 5MB/s 生效（代码 + 测试）
- `mvn -q -pl st-share -am test` EXIT=0

## 验证

- 主线程复跑 st-share 测试；抽查三处实现
