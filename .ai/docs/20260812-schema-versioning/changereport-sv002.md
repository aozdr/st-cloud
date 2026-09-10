# Change Report：SV-002 H2/MySQL schema 对比脚本

> 关联 Task: `.ai/tasks/SV-002.md`  归属: IMPLEMENTED

## 修改文件清单
| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `.ai/scripts/compare-schema.ps1` | 新增 | 对比 H2 schema.sql 与 MySQL 实际列集 + 待执行 SQL 文件清单 |

## 功能说明
- 解析 H2 schema.sql 得到列集，查询 MySQL INFORMATION_SCHEMA 得到实际列集
- 输出 ONLY_IN_H2 / ONLY_IN_MYSQL / PENDING_SQL 差异
- 退出码 0=PASS，1=有差异

## 验收标准对照
- [x] 脚本可独立运行，输出差异与待执行 SQL 文件清单
- [x] 无差异时输出 PASS（退出码 0）
- [x] 首次运行即发现 sys_user 残留 two_factor 列，已清理后 PASS

## 测试结果
- compare-schema.ps1 运行 PASS（11 张共有表全部 aligned）
- 全量回归 127 用例 0 失败