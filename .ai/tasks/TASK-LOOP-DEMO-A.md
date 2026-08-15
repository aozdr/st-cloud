# TASK-LOOP-DEMO-A（文件大小格式化器 — executor/implement）

## 元信息

- Task ID: `TASK-LOOP-DEMO-A`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 模式: lab 隔离（仅允许写 `.ai/lab/loop-demo/file-size/**`）

## 目标

1. 在 `.ai/lab/loop-demo/file-size/` 编写 `FileSizeFormatter.java`：`formatBytes(long)` 方法，将字节数格式化为 B/KB/MB/GB（1024 进制，保留一位小数），核心逻辑中文注释，含 `main` 冒烟输出 `FILE_SIZE_FORMATTER_OK`。
2. 执行中安排约 12 秒停顿（`Start-Sleep -Seconds 12`）制造并行窗口。
3. `javac -encoding UTF-8` 编译通过。
4. 返回完整 State Delta。

## 范围

- include（允许）：写入 `.ai/lab/loop-demo/file-size/**`；读取 `.ai/dispatch/**`、`.ai/tasks/TASK-LOOP-DEMO-A.md`、`.ai/knowledge/role-context.md`；在模块目录内执行 javac/java
- exclude（禁止）：`st-*/` 任何业务代码；`.ai/` 白名单外目录（含 `.ai/lab/loop-demo/file-name/**`）；修改白名单外任何文件

## 验收标准

- `FileSizeFormatter.java` 存在且编译通过；冒烟输出 `FILE_SIZE_FORMATTER_OK`
- 未触碰 `st-*` 与白名单外文件；未创建子 Agent

## 验证

- 主线程复跑 `javac -encoding UTF-8` + `java` 冒烟；检查会话日志仅写 file-size 目录
