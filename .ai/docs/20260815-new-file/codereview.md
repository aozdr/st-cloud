# Code Review：新建文件

> 归属：CODE_REVIEW（多 Agent 禁用，主线程自查记录）

## 结论

**PASS**，无阻断问题。

## 结构检查

- st-core 新增 NewFileService 职责单一（类型白名单/命名/模板/配额/事件），与上传主流程隔离 ✅
- 团队入口复用 TeamController 权限判定（upload + checkNotLocked），未复制权限逻辑 ✅
- 前后端契约一致（`POST /api/file/new` / `POST /api/team/{spaceId}/files/new`，body `{parentId, type}`）✅

## 规范与安全

- 核心逻辑（权限/命名/配额/事件）中文注释 ✅
- 类型白名单防任意后缀；个人 owner / 团队 upload 校验；配额预检 + 原子扣减（无半成品）✅
- 事件链路复用 ReliableEventPublisher（Outbox），与编辑器保存一致 ✅

## 问题清单

| 编号 | 问题 | 级别 | 处理 |
|------|------|------|------|
| R1 | 空白模板与 OnlyOffice 真实兼容性需实测（测试仅校验 ZIP 结构） | P2 | 部署环境实测 |
| R2 | 零字节 txt 走 S3 putObject(size=0) | P3 | 与现有空文件路径一致，实测覆盖 |

## 结论

实现与设计一致，进入安全审查。
