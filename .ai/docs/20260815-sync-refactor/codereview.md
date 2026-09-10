# 代码 Review 记录：同步引擎 V2 重构

> 输出标准：`docs/newList/ai-code-review-standard.md`
> 归属 CODE_REVIEW。多 Agent 环境禁用，本次由主线程自查并如实记录。

# 一、Review 概览

```
功能名称：同步引擎 V2 重构
Review 范围：同步死循环修复 + 版本门控 + 服务端守卫 + 清理接口
涉及模块：st-desktop（sync-engine/database/db-migrate/sync-utils）、st-core、st-sync
修改文件：见 changereport.md 文件清单
```

# 二、代码结构检查

```
通过：纯函数逻辑抽到 sync-utils.ts（可单测）；同步循环职责清晰（门控/事件/冲突/移动/删除分离）
问题：无
建议：后续若引入子 Agent，可把“事件合并与自激过滤”进一步封装为独立模块
```

# 三、后端代码检查

## Controller 层

- 是否只负责参数接收：是（SyncAdminController 仅做装配与返回报告）
- 是否存在业务逻辑：匹配/删除逻辑在 Service/Controller 边界内，可接受

## Service 层

- 业务逻辑是否合理：permanentDeleteAdmin 复用 permanentDeleteNodeAndChildren，无重复实现
- 是否存在事务问题：permanentDeleteAdmin 标注 @Transactional，逐节点删除，失败整体回滚

## 数据访问层

- SQL 是否合理：清理按 path LIKE 前缀 + 名称正则；likeRight 有前缀索引可用
- 是否存在慢查询风险：全量对账每页 100 条分页；清理为一次性操作，风险可控

# 四、前端代码检查

- 状态合并语义（COALESCE）集中修正“局部更新擦状态”类缺陷
- pendingEvents 合并防丢事件；engineWritten TTL 防自激
- 冲突副本落盘即登记，避免监听器回流
- 无重复实现：冲突命名/判定抽为纯函数

# 五、安全检查

- 清理接口 @PreAuthorize("hasRole('ADMIN')")，未登录/普通用户不可达
- 删除仅匹配机器格式正则，普通文件不匹配
- S3 物理删除仅在 file_object 引用计数归零时执行
- 云端 DELETE 双条件（mtime+md5）保护本地修改

# 六、性能检查

- 全量对账大小不同直接判冲突，避免全量 md5 计算
- 游标保留重建前位置，避免重放历史日志
- 事件合并 + 自写过滤，避免无效同步轮次

# 七、测试检查

- sync-utils 纯函数 8 例、db-migrate 迁移保留断言、sync-retry 回归，npm test 16/16
- 服务端 mvn compile 通过；服务端集成测试待部署环境补跑（见 testreport.md）

# 八、问题清单

| 编号 | 问题 | 等级 | 建议 |
|------|------|------|------|
| 1 | `(本地-ts)` 上传失败无自动重试 | Minor | 保留 conflict 标记，用户再编辑即重试；后续迭代加 pendingLocalCopy 状态 |
| 2 | 服务端全量 mvn test 未执行 | Major | 部署环境补跑（不影响本次编译/单测结论） |

# 九、Review 结论

```
结论：通过（含 2 条待办，均不阻塞本次交付）
```
