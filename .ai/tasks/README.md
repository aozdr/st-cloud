# .ai/tasks/

Task 驱动开发的任务文件目录。

- 中型及以上任务在进入 IMPLEMENTED 前，由 Workflow Manager 依 `design.md` + `testcases.md` 生成 `TASK-xxx.md` 落盘到本目录（命名 `TASK-<task-id>-<序号>.md`，如 `TASK-20260811-share-password-01.md`）
- 工程师编码输入只接受本目录下的 Task 文件，禁止直接接受业务需求（小型直接执行除外，对话中给出范围摘要）
- 模板见 `.ai/templates/task-template.md`
- Task 文件随项目版本管理，纳入 git，不放入 `.gitignore`