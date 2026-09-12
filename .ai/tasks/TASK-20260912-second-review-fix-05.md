# TASK：Phase 5 回收站永久删除幂等

- Task ID：`TASK-20260912-second-review-fix-05`
- State：`.ai/state/20260912-second-review-fix.yaml`
- 输入：本轮 `design.md`、`testcases.md`；角色：executor，taskType：implement

## 目标与 include

清空回收站仅处理不存在任意 RECYCLED 祖先的根；每个节点只有首次有效删除才执行配额退还、对象引用释放与事件。覆盖直接 permanentDelete、emptyRecycleBin 和既有过期清理调用。允许修改 `st-core/src/main/java/com/stcloud/core/service/impl/RecycleBinServiceImpl.java`、必要的 `mapper/FileNodeMapper.java`、直接相关的 `st-core/src/test/**`，以及本 dispatch 独立结果文件。

## exclude 与验收

禁止事务内 S3/外部 IO、自动清理无关存量数据、schema/API/UI 变化。RECYCLE-01～02 通过；父子重复输入及同节点重复删除不重复退 quota/refCount。阶段测试通过后方可进入 Phase 6。
