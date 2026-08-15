# TASK-P1-H4-BOM（全仓去除 UTF-8 BOM — executor/implement）

## 元信息

- Task ID: `TASK-P1-H4-BOM`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 全量 Code Review H4（73 文件带 UTF-8 BOM，实际扫描 70：69 st-web + 17_team_invite.sql）

## 目标

全仓去除文本文件的 UTF-8 BOM（EF BB BF 前缀），统一为 UTF-8 无 BOM，符合 `.ai/knowledge/conventions.md` 编码规范。**只改字节前缀，不改任何内容/逻辑/换行。**

## 方法

1. 全仓扫描文本文件（`.ts/.tsx/.js/.json/.css/.sql/.md/.yaml/.yml/.java/.xml` 等），排除 `node_modules/`、`target/`、`dist/`、`.git/`、`.ai/dispatch/`、二进制文件。
2. 对前 3 字节为 `EF BB BF` 的文件，读取字节、去掉 BOM 前缀、以 UTF-8 无 BOM 写回。
3. 复扫确认剩余 BOM 文件为 0（排除不可写/二进制）。
4. 运行 `npx tsc --noEmit`（st-web）验证前端构建无破坏。

## 范围

- include：`st-web/**`、`docker/mysql/init/*.sql` 及其它全仓带 BOM 的文本文件（写操作仅限去 BOM）
- exclude：`node_modules/**`、`target/**`、`dist/**`、`.git/**`、`.ai/dispatch/**`、二进制文件；禁止改动任何非 BOM 内容

## 验收标准

- 全仓复扫 BOM 文件数为 0（白名单排除项除外）
- `npx tsc --noEmit`（st-web）通过
- git diff 仅显示 BOM 移除（对 st-web 文件应无内容变化；如 git 忽略 BOM 则文件数/内容核对）

## 验证

- 主线程复扫 BOM 计数；抽查去 BOM 文件首字节为 `#`/`/`/字母（非 EF BB BF）
