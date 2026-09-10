# 需求文档 — 文件工具库（Loop 并行验证）

## 背景

验证项目 Agent Loop 在 DeepSeek 运行时缺陷（spawn 消息丢弃）下，经文件收件箱兜底派发后，两个无依赖子任务能真正并行运行，且隔离在 `.ai/lab/` 内不影响正式项目。

## 用户故事

作为云盘开发流程验证者，我希望 Loop 派发的两个独立编码子任务同时执行，以便确认多 Agent 并行开发能力可用。

## 功能范围

1. **文件大小格式化器**（`FileSizeFormatter`）：`formatBytes(long)` 将字节数格式化为 B/KB/MB/GB，核心逻辑中文注释，main 冒烟输出 `FILE_SIZE_FORMATTER_OK`。
2. **文件名清理器**（`FileNameSanitizer`）：`sanitize(String)` 将 `/\:*?"<>|` 替换为 `_` 并截断超长文件名，核心逻辑中文注释，main 冒烟输出 `FILE_NAME_SANITIZER_OK`。

## 约束

- 仅写入 `.ai/lab/loop-demo/**`（gitignored）；禁止触碰 `st-*` 业务代码。
- 两个模块无依赖，可并行开发。
- 均需 `javac -encoding UTF-8` 编译通过。

## 验收标准

- 两个模块并行执行（执行时间重叠）。
- 编译冒烟通过，输出各自 OK 标记。
- 正式项目零改动。
