# 变更报告

sync-recovery.ts 将逐文件复制、核验、删除改成同盘整项原子移动，跨盘选择根目录旁的恢复区；新增路径校验与保守重试保护。对应 11 个真实文件回归接入 npm 测试脚本。

ReindexIntegrationTest 改为显式 Spring 服务上下文和外部边界替身，添加请求及计数断言。生产配置、数据库、API 与字符串 ID 契约不变。

恢复位置、兼容限制和验证结果见 design.md、results.md。
