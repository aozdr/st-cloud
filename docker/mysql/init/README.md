# 数据库初始化脚本

本目录包含星云盘的全部数据库初始化脚本（02 ~ 36），按文件名编号顺序执行。`09b` 为 09 的补充脚本，排序位于 09 与 10 之间。

## 执行方式

### 方式一：Docker 自动初始化（推荐）

`docker-compose up` 启动 MySQL 容器时，会自动按编号顺序执行本目录下全部 `.sql` 脚本（仅在数据卷为空、即首次启动时执行）。

### 方式二：手动执行

连接 MySQL 后按编号顺序执行全部脚本：

```bash
for f in docker/mysql/init/[0-9]*.sql; do
  mysql -uroot -p --default-character-set=utf8mb4 stcloud < "$f"
done
```

> 注意：所有脚本第一行均为 `SET NAMES utf8mb4;`，且手工执行必须加
> `--default-character-set=utf8mb4`。若客户端按 latin1/GBK 连接，脚本中的 UTF-8
> 中文会被双重编码成乱码（如 `管理员` 变 `ç®¡ç†å‘˜`），2026-08-15 实测踩坑。
> **新增迁移脚本必须同样以 `SET NAMES utf8mb4;` 开头**（容器内 mysql 客户端默认
> 按 latin1 连接，不设则新脚本的中文同样会乱码）。

或仅对已有数据库执行增量脚本（09 及以后大多为 `ALTER TABLE`，均已做幂等守卫，可重复执行）。执行后按「数据库版本管理」向 `schema_version` 表登记版本。

## 脚本说明

| 顺序 | 脚本 | 说明 |
|------|------|------|
| 02 | `02_create_tables.sql` | 核心表：租户、用户、文件节点、文件分片、文件版本、分享、团队空间、团队成员、同步设备、审计日志；含初始租户与管理员数据 |
| 04 | `04_rbac_tables.sql` | RBAC 表：角色、权限、用户-角色、角色-权限；含全部权限定义、admin/user 内置角色及权限分配 |
| 05 | `05_rate_limit_tables.sql` | 传输限速规则表及限速管理权限码 |
| 06 | `06_sync_tables.sql` | 文件同步引擎表（同步根配置） |
| 07 | `07_cloud_capacity.sql` | `sys_tenant.cloud_total_capacity` 云盘总容量列，默认 100GB（幂等） |
| 08 | `08_chunk_original_size.sql` | `file_chunk.original_size` 分片原文件大小（替换上传按差值计费） |
| 09 | `09_jwt_secret.sql` | JWT 签名密钥表（AES-GCM 加密存储，运行时由主密钥解密） |
| 09b | `09b_remove_two_factor.sql` | 移除两步验证字段（功能已下线） |
| 10 | `10_drop_is_admin.sql` | 移除 `sys_user.is_admin`（改用 RBAC） |
| 11 | `11_role_data_scope.sql` | 角色数据范围字段 |
| 12 | `12_add_permissions.sql` | 补充权限码 `file:copy` / `admin:storage:manage` |
| 13 | `13_remove_ratelimit_orphan.sql` | 清理限速孤立权限码 |
| 14 | `14_add_preview_permission.sql` | 预览权限码 `file:preview` |
| 15 | `15_add_file_favorite.sql` | 文件收藏表 |
| 16 | `16_add_file_hidden.sql` | `file_node.hidden` 隐藏字段 |
| 17 | `17_team_invite.sql` | 团队空间邀请链接表 |
| 18 | `18_team_activity.sql` | 团队空间活动日志表 |
| 19 | `19_notification.sql` | 站内通知表 |
| 20 | `20_team_comment.sql` | 团队文件评论表 |
| 21 | `21_team_folder_permission.sql` | 团队文件夹权限表 |
| 22 | `22_team_member_pinned.sql` | 团队成员置顶字段 |
| 23 | `23_file_lock.sql` | 文件锁定字段（locked_by / locked_at / lock_expire_at） |
| 24 | `24_team_role.sql` | 团队自定义角色表 |
| 25 | `25_team_external.sql` | 外部协作者字段 + 空间外部协作配置表 |
| 26 | `26_sync_change_log.sql` | 同步变更日志（id 游标）+ 冲突策略字段 |
| 27 | `27_sync_exclusion_conflict.sql` | 同步排除路径表 + 冲突记录表 |
| 28 | `28_file_object.sql` | 文件对象表（同租户 MD5 去重 / 引用计数）+ `file_node.object_id` |
| 29 | `29_event_log.sql` | 事件 Outbox 表（FILE_INDEX / SYNC_CHANGE，事务性可靠事件） |
| 30 | `30_sync_change_log_event_log_id.sql` | `sync_change_log.event_log_id`（MQ 消费者幂等键） |
| 31 | `31_schema_version.sql` | Schema 版本记录表（基线 `20260811.1` 记录 02~25） |
| 32 | `32_file_block.sql` | 文件块布局表（块级增量同步，5MB 块） |
| 33 | `33_share_allow_download.sql` | `file_share.allow_download` 下载/流式统一开关 |
| 34 | `34_team_folder_permission_permissions.sql` | `team_folder_permission.permissions` 权限点 JSON |
| 35 | `35_file_share_permissions.sql` | `file_share.permissions` 权限点 JSON |
| 36 | `36_editor_version_source.sql` | `file_version.source` 版本来源（0 上传覆盖 / 1 在线编辑器保存） |

## 默认数据

- 默认租户：id=1，名称「默认租户」，编码 `default`
- 默认管理员：id=1，用户名 `admin`，密码 `admin123`（BCrypt 加密），已分配 `admin` 角色
- 云盘总容量：默认租户 100GB（07 号脚本，可通过管理后台「存储管理」调整）
- 内置角色：`admin`（全部权限）、`user`（基础文件操作权限，排除管理类）

## 注意事项

1. 02/04/05/06 等建表脚本使用 `CREATE TABLE IF NOT EXISTS` 与 `INSERT ... ON DUPLICATE KEY UPDATE`，重复执行不会产生重复数据。
2. 07 及以后的增量脚本（`ALTER TABLE` / 数据迁移）均已做幂等守卫（`information_schema` 存在性检查或按条件更新），可重复执行。
3. 密码 `admin123` 仅为初始化默认值，生产环境请立即修改。
4. 每次数据库变更必须同步 `st-core/src/test/resources/schema.sql`（H2 测试库），并以 `.ai/scripts/compare-schema.ps1` 核对与 MySQL 的列差异；未通过不得标记测试通过。
5. 已有数据库升级时，若使用 Docker 卷则不会自动执行新脚本，需手动执行并按版本号记录到 `schema_version`。
