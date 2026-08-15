# TASK-PERM-TEST（权限模型改造测试验证 — tester/test）

## 元信息

- Task ID: `TASK-PERM-TEST`
- 归属 Agent: tester（taskType=test）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 权限模型重设计（DB 34/35 迁移 + BE1 st-team 权限核心 + BE2 st-share 分享上限）实现完成后，由 tester 独立串行验证

## 目标

对权限模型改造做串行测试验证（**单一进程，禁止并行 mvn**）：

1. 运行 `mvn -q -pl st-team,st-share -am test`（单进程串行，-am 带上游 st-common/st-auth/st-core/st-team）。
2. 核对权限模型关键行为：
   - st-team：权限集解析、文件夹增强并集（上传者 {view,upload} + member 规则 {download} → {view,upload,download}）、`all` 规则、自定义角色（>=100）、管理员直通、查看者预设仅 view（download=false）。
   - st-share：分享创建/更新权限 ⊆ 用户有效权限 + `share` 权限点前置、allow_download 与权限集含 download 联动、下载/流式按权限集判断。
3. 输出测试报告 `.ai/docs/20260814-permission-model/testreport.md`：执行命令、各模块测试统计（Tests run/Failures/Errors）、权限模型关键用例核对结果、失败问题清单（等级+位置+根因）。

## 范围

- include（读）：`st-team/**`、`st-share/**`、`docker/mysql/init/34_*`、`35_*`、`.ai/docs/20260814-permission-model/design.md`、`.ai/dispatch/**`
- include（写）：`.ai/docs/20260814-permission-model/testreport.md`
- exclude：修改任何业务代码、数据库脚本、前端、创建子 Agent

## 验收标准

- `mvn -q -pl st-team,st-share -am test` 单进程串行执行完成
- testreport.md 含测试统计与权限模型关键用例核对；失败项列出问题清单
- 未修改任何业务代码

## 验证

- 主线程核对 testreport.md；失败项进入 rework
