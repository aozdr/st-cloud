# 程序设计文档：实现阶段 Git Worktree 隔离（AI 工作流 V15）

# 一、需求分析

## 功能名称

实现阶段 Git Worktree 文件系统隔离（AI Agent Loop V15）

## 功能描述

目标：解决多个实现子 Agent 并发写同一工作目录导致的互相覆盖、读到半成品、构建产物互相破坏问题。每个任务在独立 worktree 中产出代码，由主线程统一合并与验证，不引入"提交即污染"副作用。

```
用户（Workflow Manager）：
  规划实现批次（TASK 文件零重叠）→ 逐个创建 worktree → 写收件箱 → spawn 子 Agent
  → 收集后核对改动范围 → 提交并合并 → 串行集成验证 → 清理
系统行为：
  子 Agent 只在 worktreeRoot 内改源码，禁 git / mvn
  主工作树在实现批次期间源码零改动（隔离断言）
最终结果：
  实现批次完成后主工作树只包含全部子任务合并后的源码；任何越界写入可通过 git status 检出
```

# 二、系统影响分析

## 影响模块

| 类型 | 模块 | 是否修改 |
|------|------|---------|
| 工作流 | `.ai/templates/dispatch-template.md` | 修改 |
| 工作流 | `.ai/knowledge/parallel-dispatch-runtime-v8.md` | 修改 |
| 工作流 | `.ai/knowledge/task-isolation-migration.md` | 修改 |
| 工作流 | `AGENTS.md` | 修改 |
| 脚本 | `.ai/scripts/worktree.ps1` | 新增 |
| 配置 | `.gitignore` | 修改 |
| 版本 | `AI-AGENT-LOOP-VERSION.txt` | 修改 |
| 产品代码 | `st-*` | 不修改 |

## 影响文件预测

- 新增：`.ai/scripts/worktree.ps1`、`.ai/docs/20260817-worktree-isolation/design.md`
- 修改：`.ai/templates/dispatch-template.md`、`.ai/knowledge/parallel-dispatch-runtime-v8.md`、`.ai/knowledge/task-isolation-migration.md`、`AGENTS.md`、`.gitignore`、`AI-AGENT-LOOP-VERSION.txt`
- 删除：无

# 三、整体设计方案

## 3.1 批次生命周期

1. 规划批次：N 个实现 TASK，文件零重叠（沿用现有 scope 规划，主线程负责）
2. 顺序准入（沿用 V14，不并发创建）：`git worktree add -b codex/<taskCode> .ai/worktrees/<taskCode> main` → 写 `inbox-<dispatchId>.md` → spawn child
3. 子 Agent 执行：在主仓库 `mainRoot/.ai/dispatch/` 认领收件箱；只读 `mainRoot/.ai/tasks/` 与 `mainRoot/.ai/state/`；所有源码修改只落在 `worktreeRoot`；完成后 changereport 写回 `mainRoot/.ai/docs/<task-id>/`
4. 主线程核对：`git -C <wt> status --porcelain` 的改动文件 ⊆ 该任务 scope.include；主工作树 `git status` 必须为空（隔离断言）
5. 提交与合并：主线程 `git -C <wt> add -A` + `git -C <wt> commit` → 主工作树 `git merge --no-ff codex/<taskCode>`
6. 集成验证：主工作树串行执行 `mvn test` + `npm run build`（保留 V8.4 测试串行化）
7. 清理：验证通过后 `git worktree remove <wt>` + `git branch -d codex/<taskCode>`；失败保留现场，禁止 `--force` 删除

## 3.2 角色与权限

| 对象 | 职责 | git 能力 |
|------|------|---------|
| 主线程 | 创建 worktree、写收件箱、核对范围、提交合并、集成验证、清理 | 全部 git 写操作（沙箱对 `.git` 只读，需提权） |
| 子 Agent | 只写 `worktreeRoot` 内源码 + changereport | 无（禁调 git；worktree 无依赖目录，mvn 无法执行） |
| 主工作树 | 实现批次期间源码冻结，只承载 `.ai/` 协调产物 | 源码只读 |

## 3.3 Dispatch Envelope 新增字段

```text
worktreeRoot: D:\code\st-cloud\.ai\worktrees\<taskCode>   # 唯一可写源码根
mainRoot:     D:\code\st-cloud                            # 协调文件读取 / changereport 写回
forbidGitMvn: true                                        # 禁 git / mvn，验证统一由主线程
```

## 3.4 隔离断言

实现批次期间主工作树源码必须零改动。任何子 Agent 越界写主工作树，`git status --porcelain` 立即暴露；核对不通过则不合并、不进入验证阶段。

# 四、前端设计

不适用（本任务不涉及产品 UI 改动；FE worktree 为纯源码检出，无 node_modules，构建验证统一在主线程合并后执行）。

# 五、后端设计

不适用（本任务不涉及产品后端 API / 数据模型；Maven 构建统一由主线程串行执行，规避 `~/.m2` 全局锁）。

# 六、数据库设计

无数据库变更。

# 七、安全设计

- git 写操作只由主线程执行，子 Agent 无 git 能力
- worktree 清理禁止 `--force`；未提交或失败的现场保留，不删除
- 合并前核对改动文件 ⊆ scope.include，越界即判定违规、不合并
- `.ai/worktrees/` 加入 `.gitignore`，防止嵌套 worktree 内容被主工作树误 staging

# 八、性能设计

- worktree 为纯源码检出（无 node_modules / target），每任务磁盘成本约 5 MB
- worktree 各自拥有独立 target 目录，消除 `-am` 共享上游 target 的互相破坏；`~/.m2` 全局锁仍要求 mvn 串行（保留 V8.4）
- FE 构建验证在主线程合并后统一执行，不在 worktree 内安装依赖

# 九、开发计划

```text
Task1: 基础设施落地（.gitignore、worktree.ps1、dispatch-template V9、知识库文档、AGENTS.md、版本号）
Task2: 试点验证（2 后端 + 1 前端真实小任务批次：隔离断言、提交合并、集成验证、清理）
Task3: 收尾（试点报告、知识库沉淀、V15 定版）
```

# 十、遗留问题点（Grill Me 拷打收敛）

| 编号 | 遗留问题 | 影响 | 建议方案 | 用户裁决 |
|------|---------|------|---------|---------|
| P1 | 实现批次进行中主线程源码冻结：批次期间主线程不得改源码，否则合并冲突 | 批次期间人工改码受限 | 批次期间主线程只写 `.ai/`；确需改码则暂停批次，待批次合并后另行处理 | 待确认 |
| P2 | 集成验证失败时 worktree 现场保留多久 | 现场占用磁盘与分支，长期不清理造成堆积 | 保留至该迭代验收（ACCEPT）后，由主线程清理并记录 | 待确认 |
| P3 | merge 后分支是否 push 到 origin | 涉及远程仓库变更范围 | 默认仅本地合并不 push；推送由用户手动决定 | 待确认 |

# 十一、风险分析

| 风险 | 影响 | 解决方案 |
|------|------|---------|
| 沙箱对 `.git` 只读 | 每次 git 写操作需提权 | 申请窄前缀规则（git worktree / commit / merge / branch / add），批准后免逐次确认 |
| 子 Agent 越界写主工作树 | 隔离断言失效 | 合并前核对 git status 与改动清单 ⊆ scope，越界不合并 |
| 合并冲突（零重叠规划失效） | 批次阻塞 | 冲突时保留现场、人工裁决；可回退 V14 共享目录模式 |
| FE worktree 无 node_modules | 子 Agent 无法本地构建 | 构建验证统一由主线程执行，默认不在 worktree 内构建 |
| `git worktree add` 失败 | 批次无法创建 | 自动降级 V14 共享目录 + scope 白名单模式 |
