# TASK-W-LOW（W4-W8 低风险治理：注释/文档同步 — executor/implement）

## 元信息

- Task ID: `TASK-W-LOW`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 全量 Code Review W4/W5/W6/W7/W8（注释漂移与文档-实现脱节）

## 目标

低风险治理：同步注释与文档，消除漂移；**不改业务逻辑、不重命名组件文件**（文档豁免说明即可）。

## 修改清单

1. **W4 注释漂移（st-core）**：
   - `FileNode.java:27`：`uploadStatus` 注释同步为 0-待上传 1-上传中 2-已完成 3-失败 4-合并中 5-已删除。
   - `FileNode.java:39`：删除/修正 `lockExpireAt` 残留的「0-正常 1-隐藏」注释。
   - `UploadStatus.isTerminal()`（:37 注释）与 `FileNodeMapper.claimMerging`（`upload_status IN (1,3)`）语义矛盾：若 FAILED(3) 确实允许流转到 MERGING(4)，则修正 `isTerminal` 语义（注释或逻辑，以业务为准）并确保 `mvn -pl st-core -am test` 通过。
2. **W5 前端文档（st-web/.ai）**：
   - `frontend.md`：补充 ui 组件小驼峰命名豁免说明（shadcn 风格）；`useUpload.tsx` 扩展名说明；favorites store 路径修正为 `src/store/favorites.ts` 并补收录。
3. **W6 XML Mapper 文档**：
   - `conventions.md` / `architecture.md`：把「复杂查询使用 XML Mapper」更新为「注解式 SQL 为主（含 `<script>` 动态 SQL），XML 为可选」。
   - `st-api/src/main/resources/application.yml`：`mapper-locations` 配置旁加注释说明（保留配置不删）。
4. **W7 SQL 状态注释**：在 `FileNodeMapper`、`FileObjectMapper`、`EventLogMapper`、`FileChunkMapper`、`CloudCapacityMapper`、`StatsMapper` 的关键 SQL 旁补充状态含义中文注释（不改 SQL）。
5. **W8 文档豁免**：`frontend.md`/`architecture.md` 补充：Controller 流式下载（streamFile/downloadAsZip）void 直写响应为合理例外；前端 `any` 属待改进项说明。

## 范围

- include（写）：`st-core/.../entity/FileNode.java`、`enums/UploadStatus.java`、`mapper/FileNodeMapper.java` 等 Mapper（仅注释）、`st-api/src/main/resources/application.yml`（仅注释）、`.ai/knowledge/frontend.md`、`conventions.md`、`architecture.md`
- exclude：修改任何业务逻辑/SQL 语句、`st-web` 组件文件（不重命名）、创建子 Agent

## 验收标准

- FileNode 注释与 UploadStatus 枚举一致；isTerminal/claimMerging 语义无矛盾（如改逻辑则 st-core 测试通过）
- frontend.md/conventions.md/architecture.md 与实现一致
- Mapper SQL 关键处有状态注释；SQL 语句零改动

## 验证

- 主线程抽查注释与文档；如改 isTerminal 逻辑则复跑 st-core 测试
