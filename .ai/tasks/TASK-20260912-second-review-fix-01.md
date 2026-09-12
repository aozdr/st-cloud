# TASK：Phase 1 完整文件 MD5

- Task ID：`TASK-20260912-second-review-fix-01`
- State：`.ai/state/20260912-second-review-fix.yaml`
- 输入：`.ai/docs/20260912-second-review-fix/design.md`、`testcases.md` 与用户提供的第二轮 Review 修复计划
- 角色：executor；taskType：implement

## 目标

Web 和 Desktop 上传的 `fileMd5` 统一为 `MD5(all file bytes)`；Desktop 同步状态不得继续使用采样值作为内容指纹。完成 HASH-01～03，并运行本阶段测试与 Web/Desktop 类型或构建检查。

## include

- `st-web/src/hooks/useUpload.tsx` 与必要的 `st-web/src/lib/*` hash 辅助文件
- `st-desktop/src/utils/md5.ts`、`st-desktop/src/upload-manager.ts`、`st-desktop/src/sync-engine.ts`、`st-desktop/src/sync/sync-upload.ts`
- 上述两端的必要 hash contract 测试文件和测试脚本
- `.ai/runtime/results/<本次 dispatchId>.json`

## exclude

- 后端、团队模块、数据库、UI 视觉与页面结构
- SHA-256、采样 hash 新协议、无关同步逻辑重构
- 现有未提交文件删除或覆盖

## 验收与验证

- 0B、1KB、5MB、11MB、100MB+ 和相同前缀不同内容用例直接调用生产 hash 实现，并与 Node crypto 完整 MD5 对照。
- Web 和 Desktop 结果一致；不再将采样 hash 传入上传 check/init 或写入内容 MD5 同步状态。
- 运行 hash contract 测试、`st-web` build、`st-desktop` typecheck/build 中与本阶段相称的命令；记录真实结果。
- 禁止修改其他 Phase；Phase 1 测试通过前不得进入 Phase 2。
