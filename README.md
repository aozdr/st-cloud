# 星云盘（st-cloud）

免费、可私有化部署的自托管云盘系统。基于 Spring Boot 3 + React 18 + Electron 构建，覆盖 Web、PC 桌面端与移动端（PWA / Capacitor），同时服务个人网盘与企业团队协作场景。

## 功能特性

### 文件管理

- 文件夹/文件 CRUD、批量操作、拖拽上传、移动/复制/重命名
- 回收站（默认保留 30 天）、恢复与永久删除
- 文件版本历史与恢复，版本来源标记（上传覆盖 / 在线编辑器保存）
- 收藏、隐藏文件、文件锁定（防止并发编辑冲突）

### 上传与存储

- 分片上传、断点续传、上传状态机、并发窗口控制
- 秒传去重：同租户 MD5 唯一，`file_object` 引用计数，物理对象只存一份
- 三重配额校验：个人配额 / 团队空间配额 / 云盘总容量
- S3 兼容对象存储（RustFS / MinIO），元数据存 MySQL

### 团队协作

- 团队空间、成员管理、内置角色（管理员/编辑者/查看者）与自定义角色
- 文件夹级权限覆盖（细粒度权限点）、外部协作者
- 文件评论、活动日志、站内通知、成员置顶

### 分享

- 链接分享、提取码、有效期、访问审计
- 查看 / 下载 / 上传 / 编辑权限点，下载开关

### 文件同步

- 本地文件夹与云端双向同步（st-sync 引擎）
- 增量同步与块级增量（5MB 块 MD5 对比，S3 UploadPartCopy 复用未变块）
- 冲突检测与处理（保留副本等策略）、排除路径、WebSocket 实时推送

### 搜索、预览与在线编辑

- Elasticsearch 文件名 + 内容全文搜索（IK 中文分词，ingest-attachment 文档解析）
- 高级筛选：文件类型 / 时间 / 大小
- 文档预览：Office（docx/xlsx/pptx）与 PDF 通过 OnlyOffice 只读查看（全屏页 + 返回按钮，
  排版/图片与 Office 一致）；分享场景本地渲染（docx-preview / pdf.js 兜底）；图片/音视频内嵌预览
- 格式转换：Word（doc/docx）↔ PDF 一键互转（右键菜单，转换名可编辑，重名自动按云盘规则处理）
- OnlyOffice 在线编辑 Word / Excel / PPT：编辑锁（Redis）、回调验签、SSRF 防护

### 系统管理

- 用户 / 角色 / 权限管理、审计日志、仪表盘统计
- 传输限速（令牌桶，管理员按用户/角色配置，默认关闭）
- 存储管理与容量调整

### 多端与部署

- Web：React + Vite，PWA 离线可用
- 桌面端：Electron 31，原生上传/下载、同步、系统集成
- 移动端：PWA 适配 + Capacitor Android 壳（企业内部分发）
- SaaS 多租户 / 私有化单租户双模式

## 技术栈

| 端 | 技术 |
|----|------|
| 后端 | Java 17、Spring Boot 3.2.5、MyBatis-Plus、MySQL 8、Redis 7、Elasticsearch 8.12、RocketMQ 5（事件 Outbox）、AWS S3 SDK、Spring Security + JWT + RBAC、Knife4j |
| Web | React 18、TypeScript、Vite 5、Tailwind CSS、Radix UI、Zustand、React Router 7、Recharts、PWA |
| 桌面端 | Electron 31、TypeScript、tsup、electron-builder、sql.js、ws |
| 移动端 | Capacitor 8（Android 壳） |

## 项目结构

```
st-cloud/
├── .ai            # AI 协作研发工作流（任务、知识库、脚本，规则见 AGENTS.md）
├── st-common      # 公共基座：实体、枚举、工具、异常、响应、配置、传输限速
├── st-auth        # 认证授权：JWT、登录注册、用户实体、安全配置
├── st-core        # 核心模块：文件、上传下载、版本、配额、回收站、在线编辑
├── st-share       # 文件分享：链接、提取码、权限点、审计
├── st-team        # 团队协作：空间、成员、角色、文件夹权限、外部协作者
├── st-sync        # 文件同步引擎：增量、块级、冲突处理、WebSocket
├── st-search      # 全文搜索：ES 索引、监听消费、索引重建
├── st-preview     # 文档预览：Office / PDF / 图片 / 音视频
├── st-admin       # 系统管理：用户、角色、权限、审计、统计、限速、容量
├── st-api         # 启动聚合模块（唯一可启动的后端入口）
├── st-web         # Web 前端（React + Vite + PWA + Capacitor）
├── st-desktop     # 桌面客户端（Electron）
├── docker         # Docker Compose 编排、MySQL 初始化脚本、ES 自定义镜像
│   └── mysql/init # 数据库初始化脚本（02 ~ 36，含说明 README）
└── scripts        # 运维脚本（同步垃圾清理等）
```

后端 Maven 模块依赖方向严格自上而下：`st-common` 是基座，`st-api` 聚合其余模块启动。

## 快速开始

### 环境要求

- JDK 17+、Maven 3.8+
- Node.js 18+
- Docker（推荐方式启动 MySQL / Redis / 对象存储 / ES / RocketMQ / OnlyOffice）
- 建议内存 4GB+（OnlyOffice 社区版官方要求 2 核 2GB 起）

### 1. 启动依赖服务

```bash
cd docker
docker-compose up -d
```

服务清单：

| 服务 | 端口 | 说明 |
|------|------|------|
| mysql | 3306 | MySQL 8.0，首次启动自动执行 `mysql/init/` 下全部脚本（脚本首行 `SET NAMES utf8mb4;`） |
| redis | 6379 | Redis 7（缓存/会话/编辑锁/限速） |
| rustfs | 9000 / 9001 | S3 兼容对象存储 |
| rocketmq-namesrv | 9876 | RocketMQ NameServer |
| rocketmq-broker | 10909-10912 | RocketMQ Broker（自动创建 topic） |
| rocketmq-dashboard | 9080 | RocketMQ 控制台（可选） |
| elasticsearch | 9200 | ES 8.12，自定义镜像内置 ingest-attachment + IK 插件 |
| onlyoffice | 8081 | OnlyOffice Document Server（在线编辑 / 只读预览 / 格式转换） |

> Elasticsearch 镜像首次构建需要联网下载插件（Elastic 官方源 + infinilabs IK 源）；网络受限环境请提前配置代理或预构建镜像。

> OnlyOffice 容器只读挂载宿主机 `C:/Windows/Fonts`（Windows 下 docx 中文字体按宋体/微软雅黑等原字体渲染；
> Linux 生产部署需自行安装对应字体，见 `.ai/knowledge/business-domain.md`）。

也可以跳过 Docker，使用本地安装的 MySQL / Redis / S3，并修改 `st-api/src/main/resources/application-dev.yml`。

### 2. 初始化数据库

- Docker 首次启动会自动按编号顺序执行 `docker/mysql/init/` 下全部脚本（仅空数据卷时）。
- 已有数据库：按脚本编号手动执行，并用 `schema_version` 表记录版本（详见 [docker/mysql/init/README.md](docker/mysql/init/README.md)）。
- 默认数据：租户「默认租户」、管理员 `admin / admin123`、云盘总容量 100GB。

### 3. 启动后端

```bash
mvn clean install -DskipTests
cd st-api
mvn spring-boot:run
```

后端监听 `http://localhost:8080`，API 文档（Knife4j）：`http://localhost:8080/doc.html`。

### 4. 启动 Web 前端

```bash
cd st-web
npm install
npm run dev
```

前端监听 `http://localhost:5173`，`/api` 请求由 Vite 代理到后端。

### 5. 启动桌面端

```bash
cd st-desktop
npm install
npm run dev
```

`npm run dev` 会同时启动 Web 开发服务器与 Electron 窗口。生产打包：`npm run build`（NSIS 安装包输出到 `release/`）。

### 6. 登录

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | admin123 | 系统管理员（全部权限） |

> 生产环境务必立即修改默认密码。

## 配置说明

主配置：`st-api/src/main/resources/application.yml`；开发覆盖：`application-dev.yml`。

关键配置项：

| 配置 | 说明 | 默认值 |
|------|------|--------|
| `server.port` | 后端端口 | 8080 |
| `stcloud.tenant.mode` | 租户模式 SAAS / PRIVATE | SAAS |
| `stcloud.jwt.expiration` | Access Token 有效期 | 7 天 |
| `stcloud.jwt.refresh-expiration` | Refresh Token 有效期 | 30 天 |
| `stcloud.storage.*` | S3 对象存储（endpoint / bucket） | RustFS 127.0.0.1:9000 |
| `stcloud.elasticsearch.uris` | ES 地址 | 127.0.0.1:9200 |
| `stcloud.onlyoffice.url` | OnlyOffice 前端地址 | http://localhost:8081 |

环境变量（生产必设）：

| 环境变量 | 说明 |
|----------|------|
| `STCLOUD_MASTER_KEY` | JWT 主密钥，用于加解密数据库中的签名密钥（≥32 字节） |
| `STCLOUD_CORS_ORIGINS` | 允许的跨域来源，逗号分隔；留空拒绝所有跨域 |
| `STCLOUD_S3_ACCESS_KEY` / `STCLOUD_S3_SECRET_KEY` | 对象存储凭证（开发默认 stcloud / stcloud123） |
| `STCLOUD_ONLYOFFICE_SECRET` | OnlyOffice 签名密钥（≥32 字节），docker-compose 与后端共用 |
| `MYSQL_ROOT_PASSWORD` | docker-compose 中 MySQL root 密码（默认 123456） |

示例见 [docker/.env.example](docker/.env.example)。

## 客户端服务器地址配置

桌面端与 Web 端均支持自定义服务端地址，**无需登录即可配置**：

- 桌面端：登录页底部「服务器设置」（仅 Electron 显示）
- Web 端：访问 `http://<前端地址>/server-config` 路由

保存后立即刷新 API 基址（`<服务器地址>/api`），并持久化（Web 存 `localStorage`，桌面端存 `userData/server-config.json`）。默认地址 `http://127.0.0.1:8080`。

## 数据库版本管理

数据库含 `schema_version` 表，每次迭代以 `YYYYMMDD.N` 版本号记录执行的 SQL 文件清单。数据库变更必须按以下顺序执行：

1. 在 `docker/mysql/init/` 新增编号递增的 `.sql` 迁移脚本
2. 同步 H2 测试库 `st-core/src/test/resources/schema.sql`
3. `mvn test` 全绿（含 SchemaConsistencyTest 校验）
4. 运行 `.ai/scripts/compare-schema.ps1` 对比 MySQL 实际 schema，确认无列差异
5. 将新增脚本执行到运行中的 MySQL，并向 `schema_version` 插入版本记录
6. 重新运行 `compare-schema.ps1` 确认 PASS（退出码 0）

## 测试

```bash
# 后端（H2 内存库，含集成测试）
mvn test

# Web 前端
cd st-web && npm run lint && npm run build

# 桌面端（sync 引擎单元测试）
cd st-desktop && npm run test
```

## 运维脚本

| 脚本 | 说明 |
|------|------|
| [scripts/cleanup-sync-junk.ps1](scripts/cleanup-sync-junk.ps1) | 清理同步冲突产生的机器格式垃圾副本（默认 dry-run，`-Apply` 执行） |
| [.ai/scripts/compare-schema.ps1](.ai/scripts/compare-schema.ps1) | H2 schema 与 MySQL 实际 schema 列对比 + 待执行 SQL 检测（数据库变更门禁） |

## 许可证

MIT License，见 [LICENSE](LICENSE)。
