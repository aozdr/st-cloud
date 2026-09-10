# 测试用例：新建文件

> 归属：TESTCASES（依赖 DESIGN done；实现前完成）

## 测试范围

后端（NewFileService/接口/权限/配额/事件/类型白名单）+ 前端（菜单/跳转/错误提示）+ 构建

## 用例清单

| 编号 | 场景 | 预期 |
|------|------|------|
| TC-01 | 个人目录新建 txt | 生成「新建文本文档.txt」，内容为空文本，可预览/下载 |
| TC-02 | 新建 docx | 生成「新建文档.docx」，模板可被 OnlyOffice 打开（字节为合法 OOXML） |
| TC-03 | 新建 xlsx / pptx | 同上（合法 OOXML 字节） |
| TC-04 | 同目录重名 | 第二次新建自动追加 (1)，第三次 (2) |
| TC-05 | 个人目录非 owner 新建 | 403 |
| TC-06 | 团队目录：有 upload 权限 | 可新建；查看者（无 upload）拒绝 |
| TC-07 | 配额不足 | STORAGE_QUOTA_EXCEEDED，且不产生半成品节点 |
| TC-08 | 事件链路 | 新建后发布 FileIndexEvent + SyncChangeEvent(CREATE)，sync_change_log 出现记录 |
| TC-09 | 非法 type | 400/业务异常，白名单外类型拒绝 |
| TC-10 | 新建后 file_node 状态 | status=正常、uploadStatus=已完成、owner/space 归属正确 |
| TC-11 | 前端菜单显隐 | 个人/团队 upload 目录显示新建菜单；无权限不显示 |
| TC-12 | 新建 Office 后跳转编辑 | docx/xlsx/pptx 新建成功后跳 /file/:nodeId/editor；txt 留在列表 |
| TC-13 | 新建失败提示 | 配额不足/权限不足 toast 提示，不跳转 |
| TC-14 | 构建 | mvn test（含新集成测试）+ npm run build / tsc 通过 |

## 覆盖要求

- 每个验收标准至少一条用例（requirement 七 ↔ TC-02/04/06/01/07）
- 涉及 Mapper 的 Service 必须有集成测试（NewFileService）
- 越权与配额用例强制（TC-05/07）
