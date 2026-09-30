# 当前修订代码自检

执行者 workflow-manager，用户授权同一执行者独立完成；这是自检，不是独立评审。修订 team-recycle-code-8d9bce85490e655a。

按本任务 baseline 三份副本对照当前范围：生产只改回收站 service 和既有 core 团队 mapper；前端/正常文件权限入口未放宽。查询由同 tenant 的个人 owner 或授权 space 决定，member/space 删除、过期、禁用和外部开关均即时核对。团队父目录恢复不再错误要求 owner 相同；同名查询传 owner=null，并调用既有团队重名方法。路径更新沿用同 tenant/space SQL。恢复事件过滤相同 scope，混合 owner 团队子孙保留；独立回收子节点不被改成正常。

永久删除只接受回收态根，递归按团队空间覆盖不同 owner；个人仍同时筛 owner/personal。受影响行数仍认领副作用，重复ID不重复扣配额。清空与列表共用范围，祖先判断同团队不再要求同 owner。正常个人入口 getNodeByIdAndOwner 继续拒绝团队。

测试验证：6套最终67条全部通过，真实MySQL18条未跳过；真实HTTP执行目录删除→回收列表→拥有者/管理员恢复与编辑者拒绝，混合owner子孙路径和空间正确，个人兼容和永久删除通过。结构两次比对与 scoped diff --check exit0。未发现本范围阻断项。全应用长时并发、权限撤销与写入同时发生等专项压力场景未执行，不以此自检声明其通过。
