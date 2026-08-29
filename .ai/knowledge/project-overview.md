# 项目总览

> 本文档由代码扫描自动生成，反映 st-cloud（星云盘）项目截至扫描时的真实状态。

## 产品定位

星云盘是一个**完全免费、可私有化部署**的自托管云盘系统，兼顾个人用户与企业团队双重场景。

- **双部署模式**：SaaS 多租户公有云 + 私有云自建（单租户）
- **双端覆盖**：Web 端（React）+ PC 桌面端（Electron），首期无移动端
- **对标产品**：Nextcloud/ownCloud 的自建灵活性 + 百度网盘/阿里云盘的体验品质
- **商业模式**：完全免费，无会员、无付费墙

## 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 编程语言 |
| Spring Boot | 3.2.5 | 应用框架 |
| MyBatis-Plus | 3.5.7 | ORM |
| MySQL | 8.0 | 关系数据库 |
| Redis | 7 | 缓存/会话/限速 |
| Elasticsearch | 8.12.0 | 全文搜索 |
| RocketMQ | 5.3.1 (spring 2.3.0) | 事件消息（Outbox 可靠事件：FILE_INDEX / SYNC_CHANGE） |
| AWS S3 SDK | 2.25.40 | 对象存储（RustFS/MinIO） |
| JJWT | 0.12.6 | JWT 认证 |
| Hutool | 5.8.27 | 工具库 |
| Knife4j | 4.5.0 | API 文档 |
| MapStruct | 1.5.5.Final | 对象映射 |
| Lombok | (Spring Boot 管理) | 代码简化 |

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| React | 18.3.1 | UI 框架 |
| TypeScript | 5.x | 类型系统 |
| Vite | (最新) | 构建工具 |
| Zustand | 5.0.14 | 状态管理 |
| React Router | 7.18.2 | 路由 |
| Tailwind CSS | - | 样式 |
| Radix UI | - | 无障碍组件 |
| Recharts | 2.15.4 | 图表 |
| Axios | 1.19.0 | HTTP 客户端 |
| spark-md5 | 3.0.2 | 前端 MD5 计算 |
| xlsx | 0.20.3 | Excel 处理 |
| plyr | 3.8.4 | 视频播放器 |
| docx-preview | 0.4.0 | Word 预览 |

### 桌面端

| 技术 | 版本 | 用途 |
|------|------|------|
| Electron | 31.0.0 | 桌面框架 |
| TypeScript | 5.5.0 | 类型系统 |
| tsup | 8.1.0 | 打包 |
| electron-builder | 24.13.3 | 安装包构建 |
| sql.js | 1.13.0 | 本地 SQLite |
| Axios | 1.7.2 | HTTP 客户端 |

## 模块拓扑

```
st-cloud (Maven 多模块, groupId: com.stcloud, version: 1.0.0-SNAPSHOT)
│
├── st-common      公共基座：实体/枚举/工具/异常/响应/配置/S3/限速
│      └── (无内部依赖)
│
├── st-auth        认证授权：JWT/登录注册/用户实体/RBAC安全配置
│      └── st-common
│
├── st-core        核心模块：文件/文件夹/上传/下载/版本/配额/回收站
│      └── st-common
│
├── st-share       文件分享：链接/提取码/有效期/审计
│      └── st-common, st-core
│
├── st-team        团队协作：团队空间/成员/角色/共享文件操作
│      └── st-common, st-auth, st-core
│
├── st-sync        文件同步：同步根/delta增量/id游标/块级增量/WebSocket推送
│      └── st-common, st-core
│
├── st-search      全文搜索：ES索引/监听/重建
│      └── st-common, st-core
│
├── st-preview     文档预览：PDF（pdf.js）/图片/音视频/文本；Office（docx/xlsx/pptx）预览走 OnlyOffice 只读查看
│      └── st-common, st-core
│
├── st-admin       系统管理：用户/角色/权限/审计/统计/限速/容量
│      └── st-common, st-auth, st-core
│
└── st-api         启动聚合：Spring Boot 主类 + 全配置
       └── st-auth, st-core, st-share, st-team, st-sync, st-search, st-preview, st-admin
```

> 依赖方向严格自上而下，`st-common` 是所有模块的基座，`st-api` 是唯一可启动的聚合模块。

## 快速启动

### 环境要求

- JDK 17+
- Maven 3.8+
- Node.js 18+
- MySQL 8.0 / Redis 7 / Elasticsearch 8 / RocketMQ 5 / S3 兼容存储（RustFS/MinIO）

### 1. 启动依赖服务

```bash
cd docker
docker-compose up -d
```

启动 MySQL、Redis、RustFS、Elasticsearch、RocketMQ（NameServer + Broker + Dashboard）、OnlyOffice（在线文档编辑，8081 端口）。

### 2. 初始化数据库

数据库脚本位于 `docker/mysql/init/`，按编号顺序自动执行：

| 脚本 | 说明 |
|------|------|
| `02_create_tables.sql` | 核心表：租户、用户、文件节点、分享、团队、审计等 |
| `04_rbac_tables.sql` | RBAC 角色、权限、关联表及初始权限/角色数据 |
| `05_rate_limit_tables.sql` | 传输限速规则表 |
| `06_sync_tables.sql` | 文件同步引擎表（sync_root） |
| `07_cloud_capacity.sql` | 云盘总容量字段（默认 100GB） |
| `08_chunk_original_size.sql` | 分片原文件大小字段 |
| `09_jwt_secret.sql` | JWT 密钥表（AES-GCM 加密存储） |
| `09b_remove_two_factor.sql` | 移除两步验证字段（功能已下线） |
| `10_drop_is_admin.sql` | 移除 is_admin 字段（改用 RBAC） |
| `11_role_data_scope.sql` | 角色数据范围字段 |
| `12_add_permissions.sql` | 补充权限码 file:copy / admin:storage:manage |
| `13_remove_ratelimit_orphan.sql` | 清理限速孤立权限码 |
| `14_add_preview_permission.sql` | 预览权限码 file:preview |
| `15_add_file_favorite.sql` | 文件收藏表 |
| `16_add_file_hidden.sql` | file_node.hidden 隐藏字段 |
| `17_team_invite.sql` | 团队空间邀请链接表 |
| `18_team_activity.sql` | 团队空间活动日志表 |
| `19_notification.sql` | 站内通知表 |
| `20_team_comment.sql` | 团队文件评论表 |
| `21_team_folder_permission.sql` | 团队文件夹权限表 |
| `22_team_member_pinned.sql` | 团队成员置顶字段 |
| `23_file_lock.sql` | 文件锁定字段（locked_by/locked_at/lock_expire_at） |
| `24_team_role.sql` | 团队自定义角色表 |
| `25_team_external.sql` | 外部协作者字段 + 空间外部协作配置表 |
| `26_sync_change_log.sql` | 同步变更日志（id 游标）+ 冲突策略字段 |
| `27_sync_exclusion_conflict.sql` | 同步排除路径表 + 冲突记录表 |
| `28_file_object.sql` | 文件对象表（同租户 MD5 去重/引用计数） |
| `29_event_log.sql` | 事件 Outbox 表 |
| `30_sync_change_log_event_log_id.sql` | sync_change_log.event_log_id（MQ 幂等键） |
| `31_schema_version.sql` | Schema 版本记录表（基线 20260811.1） |
| `32_file_block.sql` | 文件块布局表（块级增量同步） |
| `33_share_allow_download.sql` | file_share.allow_download 下载开关 |
| `34_team_folder_permission_permissions.sql` | 文件夹权限点 JSON（permissions） |
| `35_file_share_permissions.sql` | 分享权限点 JSON（permissions） |

### 3. 启动后端

```bash
mvn clean install -DskipTests
cd st-api
mvn spring-boot:run
```

后端监听 `http://localhost:8080`，API 文档 `http://localhost:8080/doc.html`。

### 4. 启动前端

```bash
cd st-web
npm install
npm run dev
```

前端监听 `http://localhost:5173`，Vite 代理 `/api` 到后端。

### 5. 启动桌面端

```bash
cd st-desktop
npm install
npm run dev   # 同时启动 web + electron
```

## 默认账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | admin123 | 系统管理员（全部权限） |

默认租户云盘总容量 100GB（`cloud_total_capacity`，07 号脚本），租户默认配额 10GB（`default_quota`）；普通用户角色 `user` 拥有基础文件操作权限（不含 `admin:*` 与 `transfer:speed:limit`）。

## 关键配置

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `server.port` | 后端端口 | 8080 |
| `stcloud.tenant.mode` | 租户模式 SAAS/PRIVATE | SAAS |
| `stcloud.jwt.master-key` | JWT 主密钥 | 环境变量 STCLOUD_MASTER_KEY |
| `stcloud.jwt.expiration` | Access Token 有效期 | 604800000ms (7d) |
| `stcloud.jwt.refresh-expiration` | Refresh Token 有效期 | 2592000000ms (30d) |
| `stcloud.storage.*` | S3 对象存储 | RustFS 127.0.0.1:9000 |
| `stcloud.elasticsearch.uris` | ES 地址 | 127.0.0.1:9200 |
| `stcloud.onlyoffice.jwt-secret` | OnlyOffice 签名密钥（≥32 字节） | 环境变量 STCLOUD_ONLYOFFICE_SECRET（开发默认值 `stcloud-onlyoffice-dev-secret-0123456789`，生产必须覆盖） |

> 生产环境务必修改 JWT 密钥、数据库密码、对象存储凭证、CORS 来源。
