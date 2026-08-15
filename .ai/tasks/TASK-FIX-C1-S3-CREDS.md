# TASK-FIX-C1-S3-CREDS（S3 凭证环境变量化 — executor/implement）

## 元信息

- Task ID: `TASK-FIX-C1-S3-CREDS`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 全量 Code Review C1（Critical：S3 凭证硬编码）

## 目标

消除 `st-api/src/main/resources/application.yml` 中对象存储访问凭证的硬编码，改为环境变量注入（保留本地开发默认值，向后兼容）。

## 修改范围（唯一文件）

- `st-api/src/main/resources/application.yml` 第 53-54 行：
  - `access-key: stcloud` → `access-key: ${STCLOUD_S3_ACCESS_KEY:stcloud}`
  - `secret-key: stcloud123` → `secret-key: ${STCLOUD_S3_SECRET_KEY:stcloud123}`
- 其它配置行（endpoint/bucket 等）保持不动。

## 兼容策略

- 使用 `${VAR:default}` 形式：未设置环境变量时沿用本地开发默认值，生产环境通过 `STCLOUD_S3_ACCESS_KEY` / `STCLOUD_S3_SECRET_KEY` 覆盖。
- 不改任何 Java 代码、不改 S3StorageConfig 绑定方式。

## 范围

- include：`st-api/src/main/resources/application.yml`
- exclude：`st-*/` 其它文件、docker/mysql/init、`.ai/`（除收件箱）、创建子 Agent

## 验收标准

- application.yml 两行改为环境变量占位符，YAML 语法正确
- 其余配置零改动；未触碰任何 Java 代码

## 验证

- 主线程 grep 确认两行已改且格式为 `${VAR:default}`；抽查 YAML 缩进
