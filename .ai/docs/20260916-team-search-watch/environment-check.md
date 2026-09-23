# 集成验证环境检查

- 日期：2026-09-16；执行者：GPT-6 主线程；性质：编码前只读环境核对。
- Maven、Java 17、Node/npm、mysql.exe 均可定位。
- 本地容器：Elasticsearch、Redis、RocketMQ、RustFS、OnlyOffice 正在运行；本机 MySQL 可由项目 compare-schema.ps1 连接。
- `.ai/scripts/compare-schema.ps1` 编码前基线退出码 0：H2 16 张表，MySQL 38 张表，共享表列一致，已有 SQL 均有版本记录。
- 此基线不替代新增 SQL/H2 产生后的迁移前与迁移后对比，未执行本轮迁移。
- st-web 现有构建命令：`npm run build`（tsc -b && vite build）；没有通用 test 脚本，仅 test:hash。测试报告不得虚构通用前端单元测试结果。
- 未启动/重启应用，未修改数据库，未重建业务 ES 索引。
