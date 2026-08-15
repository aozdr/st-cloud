# TASK-W1（UserTransferLimiter 双令牌桶去重 — executor/implement）

## 元信息

- Task ID: `TASK-W1`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 全量 Code Review W1（Duplicated Code：双令牌桶逐行重复）

## 目标

消除 `st-common/.../ratelimit/UserTransferLimiter.java` 中 `UploadPaceBucket`（:159）与 `DownloadBucket`（:191）的逐行重复：抽取公共令牌桶实现（tokens / lastRefillNs / acquire 逻辑），两个桶复用。**限速行为必须保持不变。**

## 方法

1. 在 st-common 新增公共令牌桶类（如 `TokenBucket`，含容量/速率/refill/acquire，中文注释）。
2. `UserTransferLimiter` 的 UploadPaceBucket 与 DownloadBucket 改为持有/继承公共实现，保留各自桶名、参数与语义。
3. 运行既有限速测试（`st-common/src/test` 中 UserTransferLimiter 相关测试）验证行为不变。

## 范围

- include：`st-common/src/main/java/com/stcloud/common/ratelimit/**`
- exclude：其它模块、业务主代码其它文件、`docker/mysql/init`、创建子 Agent；禁止改变限速参数/语义

## 验收标准

- 双桶无逐行重复（复用公共实现）
- `mvn -q -pl st-common -am test` EXIT=0（含限速测试）
- 限速行为与参数不变

## 验证

- 主线程复跑 st-common 测试；抽查桶实现
