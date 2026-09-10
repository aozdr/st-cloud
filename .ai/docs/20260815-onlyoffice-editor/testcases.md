# 测试用例：在线文档编辑（OnlyOffice 社区版）

> 归属：TESTCASES（依赖 TECH_DESIGN done；实现前完成）

## 测试范围

后端（权限判定 / config 生成 / 回调落盘 / 版本 / 文件保护 / 幂等 / 配额 / 事件）
+ 前端（路由 / 入口 / 错误回退 / 构建）+ 部署（docker-compose）

## 用例清单

### 权限（EditorPermissionService）

| 编号 | 场景 | 预期 |
|------|------|------|
| TC-01 | 个人文件 owner 请求 config | 返回可编辑 config（permissions.edit=true） |
| TC-02 | 个人文件非 owner 请求 config | 403 |
| TC-03 | 团队文件，文件夹权限含 upload | 可编辑 |
| TC-04 | 团队查看者（仅 view） | 只读 config（edit=false） |
| TC-05 | 分享 permissions 含 upload | 可编辑 |
| TC-06 | 非 docx/xlsx/pptx/pdf 文件请求 config | 业务异常提示不支持 |

### 回调（EditorCallbackService）

| 编号 | 场景 | 预期 |
|------|------|------|
| TC-07 | 合法 status=2（自动保存） | 覆盖 file_node 内容，不生成版本 |
| TC-08 | 合法 status=6（关闭保存） | 覆盖 + 生成 file_version（source=1）+ 移除编辑标记 |
| TC-09 | 伪造/无签名回调 | 拒绝（401/403），不落盘，记审计 |
| TC-10 | 重复回调（幂等） | 只落盘一次 |
| TC-11 | 回调 url 下载内容超大 | 拒绝落盘 |
| TC-12 | 保存后 file_node md5/size/version 更新 | 与上传内容一致 |
| TC-13 | 保存后发布 FileIndexEvent + SyncChangeEvent | 事件被发布（本地兜底链路） |
| TC-14 | 保存后配额按差值调整 | storage_used 增加/减少正确 |

### 版本与裁剪

| 编号 | 场景 | 预期 |
|------|------|------|
| TC-15 | 关闭保存生成版本 source=1 | file_version.source=1 |
| TC-16 | 上传覆盖生成版本 source=0 | 既有上传逻辑版本 source=0 |
| TC-17 | source=1 版本超 20 条 | 裁剪最旧 source=1，source=0 不受影响 |

### 文件保护

| 编号 | 场景 | 预期 |
|------|------|------|
| TC-18 | 编辑标记存在时删除/移动/重命名 | FILE_EDITING（2010）拒绝 |
| TC-19 | 编辑标记存在时覆盖上传/版本恢复 | 拒绝 |
| TC-20 | 两个用户同时打开编辑 | 标记集合两条，互不拒绝；保存串行不丢数据 |

### 下载令牌（editor 类型）

| 编号 | 场景 | 预期 |
|------|------|------|
| TC-21 | editor token 访问 stream | 放行（端点收敛 + nodeId 绑定） |
| TC-22 | editor token 用于非 stream 端点 | 拒绝 |
| TC-23 | editor token nodeId 不匹配 | 拒绝 |

### 前端 / 部署

| 编号 | 场景 | 预期 |
|------|------|------|
| TC-24 | EditorPage config 接口失败 | 错误弹窗 + 「以预览打开」回退 |
| TC-25 | 仅 docx/xlsx/pptx/pdf 且有权限显示「在线编辑」入口 | 其他格式/无权限不显示；PDF 需后端允许编辑（ONLYOFFICE Docs 8.1+） |
| TC-26 | npm run build / tsc | 通过 |
| TC-27 | docker-compose config 含 onlyoffice 服务与 extra_hosts | 通过 |
| TC-28 | SchemaConsistencyTest（file_version.source） | 三层一致 |

## 覆盖要求

- 每个验收标准至少一条用例（requirement 七 5 条 ↔ TC-01/07/08/18/09）
- 涉及 Mapper 的服务必须有集成测试（H2）：EditorCallbackService / EditorPermissionService
- 越权与回调安全用例强制（TC-09/11/22/23）
