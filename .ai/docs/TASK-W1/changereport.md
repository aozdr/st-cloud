# Change Report — W1 UserTransferLimiter 双令牌桶去重

## 背景

全量 Code Review W1（Duplicated Code）：`UserTransferLimiter` 的 `UploadPaceBucket` 与 `DownloadBucket` 逐行重复（tokens/lastRefillNs/acquire），仅注释不同。任务：抽取公共令牌桶实现，行为不变。

## 改动

1. 新增 `st-common/.../ratelimit/TokenBucket.java`：阻塞式字节令牌桶公共实现（容量 max(8192, rate)、首次满桶、按纳秒补充、不足时 sleep 等待、中断可退出）。
2. `UserTransferLimiter.java`：`UploadPaceBucket` / `DownloadBucket` 改为持有 `TokenBucket` 复用（与重构前逐行等价）。
3. 新增 `UserTransferLimiterTest`（6 条）：下载快路径/阻塞 pacing/上传快路径/中断退出/不限速/上传窗口。

## 过程中发现并修复的真实缺陷（主线程修复）

- 新测试暴露：申请量 `bytes > 桶深 max(8192, rate)` 时，`tokens` 被封顶到 capacity 永远达不到申请量 → **acquire 死循环**（重构前后原实现均存在，生产大分块下载超过桶深同样会卡死）。
- 修复：`TokenBucket.acquire` 等待上限放宽为 `max(capacity, bytes)`——空闲突发仍受 capacity 限制（防无限累积），大申请在 `(bytes-capacity)/rate` 秒后通过。

## 验证

- `mvn -q -pl st-common -am test` EXIT=0：5 个测试类全绿（UserTransferLimiterTest 6 条，2.2s，不再卡死）
- 限速参数与语义不变；公共实现消除了双份维护

## 风险

- 死循环修复属语义增强：申请超过桶深的请求从"永久卡死"变为"等待后通过"，速率上限仍保证。
