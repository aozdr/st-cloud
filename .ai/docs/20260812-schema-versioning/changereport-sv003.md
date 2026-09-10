# Change Report：SV-003 AGENTS.md DB 版本规则

> 关联 Task: `.ai/tasks/SV-003.md`  归属: IMPLEMENTED

## 修改文件清单
| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `AGENTS.md` | 修改 | 新增「数据库版本管理」章节（版本表 + 7 步强制流程 + 版本号规则） |

## 新增规则要点
- 每次迭代涉及 DB 变更须更新 schema_version 版本号
- H2 测试通过后必须运行 compare-schema.ps1 对比正式 MySQL
- 将本次执行的 SQL 文件清单写入 schema_version.applied_sql_files
- compare-schema.ps1 是强制门禁，未通过不得标记 TEST_PASS done

## 验收标准对照
- [x] AGENTS.md 含明确规则
- [x] verify-loop PASS

## 测试结果
- verify-loop PASS（FAIL=0）