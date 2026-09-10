# Security Review：在线文档编辑（OnlyOffice）

> 归属：SECURITY_REVIEW（条件项：涉及文件写入、回调、越权，必开；多 Agent 禁用，主线程自查记录）

## 结论

**PASS**，无高危问题。

## 审查项

### 1. 回调伪造与越权覆盖（高）

- 回调端点匿名可达（OnlyOffice 服务端回调所需），安全完全依赖 JWT 验签：
  - `EditorCallbackServiceImpl.verifyToken` 用 STCLOUD_ONLYOFFICE_SECRET（≥32 字节）验签，缺失 401 / 无效 403 ✅
  - 签名字段与请求体一致性复核（key/status 防 body 与签名分离篡改）✅
  - 文件归属复核：节点存在/文件/正常/已完成；key 前缀必须匹配 nodeId（防伪造回调把 A 内容写入 B）✅
- 拒绝均返回错误码并记审计日志 ✅

### 2. 越权访问（config 端点）

- 个人 config：owner 或租户管理员，其余 403 ✅
- 团队 config：TeamController 复用权限判定（成员 + upload 权限点）✅
- 分享 config：ShareController 复用分享权限（upload 权限点 + 提取码/有效期/下载开关复核）✅
- 编辑器下载 URL 用 5 分钟 editor 类型令牌（nodeId 绑定、端点收敛、不单次消费），不用长期直链 ✅

### 3. SSRF 与内容投毒（回调下载）

- 下载主机白名单（显式配置或默认 onlyoffice/localhost）+ 手动逐跳重定向复核，防重定向绕过 ✅
- 仅 http/https；大小上限（Content-Length + 流式计数 200MB）✅

### 4. 敏感信息

- OnlyOffice JWT 密钥仅经环境变量 STCLOUD_ONLYOFFICE_SECRET 注入，不入源码 ✅
- 前端 config 中不含长期凭证；document.url 令牌短期有效 ✅

### 5. 数据一致性

- 保存落盘复用 FileObject 去重/引用计数，乐观锁（@Version）防并发覆盖 ✅
- 保存锁 + 幂等键防重复落盘；事务内回滚不残留 ✅
- 编辑期间删除/移动/重命名/覆盖上传/版本恢复被 FILE_EDITING(2010) 拦截 ✅

## 风险清单

| 风险 | 等级 | 缓解 |
|------|------|------|
| STCLOUD_ONLYOFFICE_SECRET 未配置或与 docker 不一致 | 高（配置） | 启动即校验密钥长度；文档醒目说明 |
| public-base-url 不可达导致 OnlyOffice 无法回调 | 高（部署） | 配置说明 + 测试覆盖 |
| 版本裁剪保留物理对象 | 低 | 已记录，后续引用计数回收 |

## 结论

安全设计成立，可进入测试执行。
