# TASK-CODE-LAB-B（并行编码隔离验证 B — executor/implement）

## 元信息

- Task ID: `TASK-CODE-LAB-B`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 模式: lab 隔离验证（只允许在 `.ai/lab/module-b/` 内写代码，禁止触碰任何 `st-*` 业务代码）

## 目标

1. 在 `.ai/lab/module-b/` 下编写 `ModuleB.java`：`TextStats` 类，含 wordCount/charCount/lineCount 三个方法与 main 冒烟输出（输出 "MODULE_B_OK"）；核心逻辑使用中文注释。
2. 执行中安排约 15 秒停顿（`Start-Sleep -Seconds 15`），停顿前后各输出一条注释，用于制造并行执行窗口。
3. 用 `javac` 编译通过（在 `.ai/lab/module-b/` 下执行）。
4. 返回完整 State Delta（背景/输入/分析/决策/State Delta/风险/下一步/变更影响）。

## 范围

- include（允许）：
  - 写入：`.ai/lab/module-b/**`
  - 读取：`.ai/dispatch/**`（收件箱信封）、`.ai/tasks/TASK-CODE-LAB-B.md`、`.ai/knowledge/role-context.md`
  - 命令：在 `.ai/lab/module-b/` 内执行 `javac ModuleB.java`
- exclude（禁止）：
  - `st-common/**`、`st-core/**`、`st-web/**`、`st-desktop/**`、`st-sync/**`、`st-team/**`、`st-search/**`（读取或修改均禁止）
  - `.ai/` 其它目录（除上述 include 白名单）：`.ai/docs/**`、`.ai/state/**`、`.ai/knowledge/` 其它文件等
  - 修改除 `.ai/lab/module-b/` 外的任何文件

## 验收标准

- `.ai/lab/module-b/ModuleB.java` 存在，`javac` 编译通过
- 核心逻辑有中文注释；`main` 冒烟输出 `MODULE_B_OK`
- 未读取/修改任何 `st-*` 业务代码；未修改 `.ai/` 白名单外文件
- 未创建子 Agent（forbidSpawn: true）

## 验证

- 主线程复跑 `javac .ai/lab/module-b/ModuleB.java` 并运行 `java -cp .ai/lab/module-b ModuleB` 校验冒烟输出
- 主线程检查子代理会话日志：仅写 `.ai/lab/module-b/`，未触碰 `module-a` 与 `st-*`
