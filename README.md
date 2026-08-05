# 星云盘

基于 Spring Boot 3 + React 18 的自托管云盘系统，支持文件存储、团队协作、分享、同步、全文搜索与文档预览。

## 功能特性

- **文件管理**：文件夹/文件 CRUD、移动、复制、重命名、回收站（30 天保留）、版本历史与恢复
- **秒传去重**：基于 MD5 的秒传与物理对象引用计数，相同内容只存一份
- **分片上传**：大文件分片上传、断点续传、服务端门控限速
- **存储配额**：个人配额、团队空间配额、云盘总容量三重上限校验
- **团队协作**：团队空间、成员管理、角色权限（管理员/编辑者/查看者）
- **文件分享**：链接分享、提取码、有效期、访问审计
- **文件同步**：本地文件夹与云端双向同步（st-sync 引擎）
- **全文搜索**：基于 Elasticsearch 的文件名与内容搜索
- **文档预览**：Office、PDF、图片、音视频在线预览
- **系统管理**：用户/角色/权限管理、审计日志、传输限速、存储管理
- **多租户**：支持 SaaS 多租户隔离与单租户模式

## 技术栈

### 后端
- Java 17 / Spring Boot 3
- MyBatis-Plus、MySQL 8
- Redis（缓存/会话）
- Elasticsearch 8（全文搜索）
- RocketMQ（事件消息）
- AWS S3 SDK（RustFS / MinIO 等 S3 兼容对象存储）
- Spring Security + JWT + RBAC

### 前端
- React 18 + TypeScript + Vite
- Tailwind CSS + Radix UI
- Zustand 状态管理
- Recharts 图表

### 桌面端
- Electron（st-desktop）

## 项目结构

```
st-cloud/
├── st-common      # 公共模块：实体、枚举、工具、异常、响应封装
├── st-auth        # 认证授权：JWT、登录注册、用户实体
├── st-core        # 核心模块：文件、文件夹、上传、存储、版本、配额
├── st-share       # 文件分享
├── st-team        # 团队协作
├── st-sync        # 文件同步引擎
├── st-search      # 全文搜索
├── st-preview     # 文档预览
├── st-admin       # 系统管理：用户、角色、审计、统计、限速
├── st-api         # 启动模块与配置聚合
├── st-web         # 前端（React + Vite）
├── st-desktop     # 桌面端（Electron）
└── docker         # Docker Compose 编排与数据库初始化脚本
```

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- Node.js 18+
- MySQL 8.0
- Redis 7
- Elasticsearch 8（可选，用于全文搜索）
- RocketMQ 5（可选，用于事件消息）
- S3 兼容对象存储（RustFS / MinIO 等）

### 1. 启动依赖服务

使用 Docker Compose 一键启动 MySQL、Redis、RustFS、Elasticsearch、RocketMQ：

```bash
cd docker
docker-compose up -d
```

或使用本地已安装的 MySQL / Redis，并修改 `st-api/src/main/resources/application-dev.yml` 中的连接配置。

### 2. 初始化数据库

数据库初始化脚本位于 `docker/mysql/init/`，按编号顺序执行：

| 脚本 | 说明 |
|------|------|
| `02_create_tables.sql` | 核心表：租户、用户、文件节点、分享、团队、成员等 |
| `04_rbac_tables.sql` | RBAC 角色、权限、关联表及初始权限/角色数据 |
| `05_rate_limit_tables.sql` | 传输限速规则表 |
| `06_sync_tables.sql` | 文件同步引擎表 |
| `07_cloud_capacity.sql` | 云盘总容量（个人与团队共享上限） |
| `08_chunk_original_size.sql` | 分片原文件大小（替换上传按差值计费） |

**首次初始化**：Docker 容器首次启动会自动执行 `init/` 下全部脚本。

**已有数据库**：手动按顺序执行上述脚本。默认管理员账号 `admin / admin123`，默认租户云盘总容量 200GB。

### 3. 启动后端

```bash
mvn clean install -DskipTests
cd st-api
mvn spring-boot:run
```

后端默认监听 `http://localhost:8080`。

### 4. 启动前端

```bash
cd st-web
npm install
npm run dev
```

前端默认监听 `http://localhost:5173`，通过 Vite 代理转发 `/api` 到后端。

## 配置说明

主配置文件：`st-api/src/main/resources/application.yml`
开发环境覆盖：`st-api/src/main/resources/application-dev.yml`

关键配置项：

| 配置 | 说明 | 默认值 |
|------|------|--------|
| `server.port` | 后端端口 | 8080 |
| `spring.datasource.*` | MySQL 连接 | 127.0.0.1:3306/stcloud |
| `stcloud.tenant.mode` | 租户模式（SAAS/PRIVATE） | SAAS |
| `stcloud.jwt.secret` | JWT 密钥 | 开发用密钥 |
| `stcloud.storage.*` | S3 对象存储配置 | RustFS 127.0.0.1:9000 |
| `stcloud.elasticsearch.uris` | ES 地址 | 127.0.0.1:9200 |

> 生产环境务必修改 JWT 密钥、数据库密码、对象存储凭证。

## 客户端服务器地址配置

桌面端（Electron）与 Web 端均支持自定义服务端地址，**无需登录即可配置**，便于在多服务器或内网部署环境下快速切换连接目标。

### 配置入口

- **桌面端**：登录页底部点击「服务器设置」进入配置页。
- **Web 端**：浏览器访问 `http://<前端地址>/server-config` 路由。

> 登录页的「服务器设置」入口仅在 Electron 桌面端显示；Web 端可直接访问 `/server-config` 路由。

### 配置说明

| 项 | 说明 |
|------|------|
| 默认地址 | `http://127.0.0.1:8080` |
| 地址规整 | 自动补齐 `http://` 前缀、去除末尾斜杠 |
| 生效方式 | 保存后立即刷新 API 基址（`<服务器地址>/api`），无需重启 |
| 测试连接 | 探测服务端是否可达（请求 `/api/auth/login`，任意 HTTP 响应即视为连通） |

### 持久化

| 运行环境 | 存储位置 |
|------|------|
| Web 浏览器 | `localStorage`，键 `stcloud:serverUrl` |
| Electron 桌面端 | `userData/server-config.json`，启动时自动加载并同步到渲染进程 |

后端 CORS 默认放行跨域，桌面端与浏览器均可通过绝对地址直接访问服务端。

## 存储配额体系

系统采用三重配额校验，所有写入路径（上传、秒传、复制、版本恢复）都会校验：

1. **个人配额**（`sys_user.storage_quota`）：单用户文件总量上限
2. **团队空间配额**（`team_space.storage_quota`）：单团队空间文件总量上限
3. **云盘总容量**（`sys_tenant.cloud_total_capacity`）：个人与团队共享的物理存储总上限

配额以增量的形式记账：上传加、删除减、版本恢复按差值调整。回收站永久删除时退还配额并按引用计数判断是否删除 S3 物理对象。

## 默认账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin  | admin123 | 系统管理员 |

## API 文档

启动后端后访问 Knife4j 文档：`http://localhost:8080/doc.html`

## 许可证

私有项目，未开源。
