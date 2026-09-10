# Security Review：Code Review 修复迭代（20260813-code-review-fix）

## 检查项
| 项 | 结论 |
|----|------|
| 端点鉴权 | PASS：relay-chunk/relay-finalize 均 `hasAuthority('file:upload') or hasRole('ADMIN')` |
| 越权访问 | PASS：relayChunk 与 relayFinalize 均校验 owner/租户管理员（TC-007/008） |
| 请求体大小 | PASS：Content-Length > relayChunkSize 拒绝，防超大请求打满磁盘 |
| 路径穿越 | PASS：临时文件名为服务端生成（sanitize 仅留字母数字），不接受客户端路径 |
| 磁盘泄漏 | PASS：finalize/失败/超时/取消均清理临时文件；超时定时任务 abort S3 |
| S3 残留 | PASS：超时与失败路径 abort multipart（TC-009/010） |
| 限速绕过 | PASS：relay 服务端 pacing + simpleUpload 限速流（F6）修复绕过 |
| 幂等安全 | PASS：seq 请求级认领，重复请求不重复写字节 |

## 残留风险（记录不阻塞）
- `mergeChunks` / `abortUpload` 无 owner/租户权限校验（既有问题，非本迭代引入）：任意登录用户若获知 uploadId 可触发合并/中止，建议后续迭代统一加校验。
- relay 会话为内存态，多实例部署时需粘性路由或共享存储（MVP 已注明）。

## 结论
本迭代新增/修改的中转链路安全设计通过。
