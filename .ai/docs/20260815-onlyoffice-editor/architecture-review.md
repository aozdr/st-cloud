# 架构设计评审：在线文档编辑（OnlyOffice 社区版）

> 归属：TECH_DESIGN 前置（大型任务，先于 design.md）

## 评审结论

**通过**。方案采用 OnlyOffice Document Server 独立服务 + 后端 API 集成，不修改既有
权限/版本/事件主链路，风险可控。

## 整体架构

```
浏览器 (st-web EditorPage)
    │ iframe
    ▼
OnlyOffice Document Server (Docker, :8081)   ←── docker-compose 内置
    │ 1. GET document.url（加载文档）
    │ 2. POST callbackUrl（保存/关闭通知）
    ▼
st-api Spring Boot (:8080)
    ├─ GET /api/file/{nodeId}/editor/config   （登录用户 → config + JWT）
    ├─ POST /api/file/{nodeId}/editor/callback（OnlyOffice 回调 → 校验 → 落盘）
    └─ GET /api/file/{nodeId}/stream          （短期 token 文档下载）
```

## 关键设计

### 1. 集成协议（OnlyOffice Docs API）

- 前端 `new DocsAPI.DocEditor(id, config)`，config 含：
  `document{fileType,key,title,url,permissions}` + `editorConfig{callbackUrl,mode,user,lang}` + `token`
- `document.key` = `fileNodeId + ":" + version`（编辑会话内稳定，重开时版本变化即刷新缓存）
- `document.url` = 后端下载地址（带短期 token）
- `callbackUrl` = 后端保存回调

### 2. 鉴权与安全

- OnlyOffice 容器启用 JWT 模式：`JWT_ENABLED=true` + `JWT_SECRET`（环境变量 `STCLOUD_ONLYOFFICE_SECRET`）
- 后端用同一 secret 签发 config token（HS256）；OnlyOffice 校验后执行
- 回调校验：后端验签 `Authorization: Bearer` + 复核文件归属与权限，杜绝伪造回调覆盖任意文件
- 文档 URL 短期 token（复用 download-token 机制，5 分钟级 TTL）
- 权限判定统一入口 `EditorPermissionService`：个人 owner / 团队 FolderPermissionService（upload）/
  分享 FileShare.permissions（upload）

### 3. 保存流程

```
OnlyOffice 保存/关闭
  → POST callback（status=2 自动保存 / status=6,7 关闭或强制保存）
  → 验签 + 权限复核 + Redis 保存锁（editor:save-lock:{fileNodeId}）
  → 下载 OnlyOffice 提供的 url 内容 → 大小/类型校验
  → 落盘 S3（复用 FileObject 去重）→ 更新 file_node（size/md5）
  → status=6/7 时生成 file_version（P1 已裁决：关闭时一版，上限 20）
  → 配额差值更新 → 发布 FileIndexEvent + SyncChangeEvent
  → 释放编辑标记（editor:active:{fileNodeId} 移除当前用户）
```

### 4. 协同与文件保护（P2 已裁决）

- 编辑标记用 Redis Set（打开用户集合），不互斥——多人协同由 OnlyOffice 处理
- 删除/移动/重命名前检查标记非空 → 拒绝（复用 FileService 现有检查点）
- 保存回调按文件加 Redis 锁串行化，避免并发覆盖丢数据
- 编辑标记滑动 TTL（默认 2h），关闭回调显式移除

### 5. 可达性（部署关键点）

| 调用方 | 目标 | 地址 |
|--------|------|------|
| 浏览器 → OnlyOffice | 编辑器 | `http://localhost:8081` |
| OnlyOffice 容器 → 后端 | document.url / callback | `host.docker.internal:8080`（docker-compose `extra_hosts: host-gateway`） |
| 生产环境 | 全部 | 由 `stcloud.onlyoffice.public-base-url` 配置为可达地址 |

> 开发默认 `public-base-url=http://host.docker.internal:8080`；生产必须改配公网/内网可达地址。

## 性能

- OnlyOffice 单容器建议 2 核 2GB（docker-compose 注释说明，不强制 mem_limit）
- 编辑器加载与后端无关（直连 OnlyOffice），后端只承担 config/回调，压力小

## 可维护性

- 全部配置收敛到 `stcloud.onlyoffice.*`；容器随 docker-compose 起停
- 新增 st-core `editor` 包，与上传/版本主链路隔离
- 回调幂等：以 OnlyOffice key + 时间戳去重，重复回调不重复落盘

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| 回调伪造越权覆盖 | JWT 验签 + 归属/权限复核 + 审计日志 |
| document.url 不可达导致编辑器空白 | public-base-url 配置说明 + 开发默认 host.docker.internal |
| 版本裁剪误删物理对象 | 复用 ref_count 引用归零，禁止硬删 |
| OnlyOffice 自身漏洞 | 容器网络隔离、仅暴露 8081、密钥不落源码 |

## 结论

架构成立，可进入程序设计（design.md）。
