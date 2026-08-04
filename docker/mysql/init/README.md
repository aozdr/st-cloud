# 数据库初始化脚本

本目录包含星云盘的全部数据库初始化脚本，按文件名编号顺序执行。

## 执行方式

### 方式一：Docker 自动初始化（推荐）

`docker-compose up` 启动 MySQL 容器时，会自动按编号顺序执行本目录下所有 `.sql` 脚本（仅在数据卷为空即首次启动时执行）。

### 方式二：手动执行

连接到 MySQL 后，按编号顺序执行：

```bash
mysql -uroot -p stcloud < 02_create_tables.sql
mysql -uroot -p stcloud < 04_rbac_tables.sql
mysql -uroot -p stcloud < 05_rate_limit_tables.sql
mysql -uroot -p stcloud < 06_sync_tables.sql
mysql -uroot -p stcloud < 07_cloud_capacity.sql
mysql -uroot -p stcloud < 08_chunk_original_size.sql
```

或在 MySQL 客户端中：

```sql
SOURCE /path/to/02_create_tables.sql;
SOURCE /path/to/04_rbac_tables.sql;
-- ...
```

## 脚本说明

| 顺序 | 脚本 | 说明 |
|------|------|------|
| 02 | `02_create_tables.sql` | 核心表：租户、用户、文件节点、文件分片、文件版本、分享、团队空间、团队成员、同步设备、审计日志；含初始租户与管理员数据 |
| 04 | `04_rbac_tables.sql` | RBAC 表：角色、权限、用户-角色、角色-权限；含全部权限定义、admin/user 内置角色及权限分配 |
| 05 | `05_rate_limit_tables.sql` | 传输限速规则表及限速管理权限码 |
| 06 | `06_sync_tables.sql` | 文件同步引擎表（同步根配置） |
| 07 | `07_cloud_capacity.sql` | 在 `sys_tenant` 增加 `cloud_total_capacity` 列（云盘总容量），默认 100GB |
| 08 | `08_chunk_original_size.sql` | 在 `file_chunk` 增加 `original_size` 列（替换上传时记录原文件大小，用于合并时按差值计费） |

> 07、08 为增量迁移脚本，对已有数据库执行即可，重复执行会因列已存在而报错（可忽略）。

## 默认数据

- **默认租户**：id=1，名称「默认租户」，编码 `default`
- **默认管理员**：id=1，用户名 `admin`，密码 `admin123`（BCrypt 加密），已分配 `admin` 角色
- **云盘总容量**：默认租户 100GB（可通过管理后台「存储管理」调整）
- **内置角色**：`admin`（全部权限）、`user`（基础文件操作权限，排除管理类）

## 注意事项

1. 脚本使用 `CREATE TABLE IF NOT EXISTS` 与 `INSERT ... ON DUPLICATE KEY UPDATE`，重复执行核心数据脚本不会产生重复数据。
2. 07、08 是 `ALTER TABLE` 增量脚本，**只能执行一次**；列已存在时再执行会报错，属正常现象。
3. 密码 `admin123` 仅为初始化默认值，生产环境请立即修改。
