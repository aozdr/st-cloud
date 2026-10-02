# 第三轮修复影响分析

输入：已确认 requirement.md、uispec.md；当前 main 工作区源码。用户于本轮回复“执行”，接受需求与保守 relay 恢复语义。未修改用户既有文件。

| 模块 | 必要影响 |
|---|---|
| st-core 上传/解压/文本/编辑回调 | 统一失败对象的安全回收；去重仍按 tenant+MD5，事务内仅 DB |
| st-core 对象落库 | 候选对象认领需与节点、引用、配额同事务；GC 认领后不得再提交该候选 |
| 服务端 schema | 拟增持久对象上传候选表；递增 SQL、H2 schema、两次 MySQL 对比及版本登记 |
| st-desktop | types、upload-manager、db/transfer-tasks、database/db-migrate 及测试；新增模式/块大小/限速的可空列 |
| st-share | 新增分享授权缩略图响应；沿用 stream 的访问、下载开关、权限集、限额、范围及成功计数规则 |
| st-preview | 复用缩略图生成和 preview bucket 读取，新增严格图片输出能力；不使用非图片回退原图逻辑 |
| st-web | PreviewModal filmstrip 切换缩略图 URL；失败占位；不改主图和其他 UI |
| 测试与报告 | 增补 st-share/st-preview 回归，因为附件基本门禁未覆盖实际新增后端路径 |

## 现场差异
现有四个 cleanup 方法提到定时任务兜底；全仓 Java 搜索只找到回收站、outbox 和 relay 超时任务，没有找到孤儿对象扫描/删除实现。不能以日志替代真正回收。
分享 stream 每次成功会递增 download_count，缩略图为保持既有语义同样计数，不暗中取消额度消耗。
现有 st-preview 非图片 thumbnail 请求会返回原对象预签名 URL；新分享路径必须阻止这种回退。

## 范围控制
不拆 FileServiceImpl、不改微服务部署形态、不清理历史未知 S3 对象。未触碰当前未提交的 UI、main.ts、README 和 compose 修改。发现超出范围的历史物理删除问题仅记录，除非阻碍本轮正确性。
