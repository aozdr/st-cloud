# 体验评审：Web relay 取消 与 对账保护

## FE-S1 Web 中转取消

- 现状：relay 循环顺序 POST relay-chunk，无暂停/取消；UploadPanel 的 X 按钮仅从队列移除任务，上传继续并在后台完成 relay-finalize 落库，与用户预期冲突。
- 要求：中转上传中点击 X 应立即中止：置任务为"已取消"（或直接移除），调用 `DELETE /file/upload/abort?uploadId=&s3UploadId=&fileId=` 通知服务端 abort S3 并清理缓冲会话；若请求已进入 finalize 阶段则等待其返回后 abort。
- 状态展示：任务短暂显示"取消中"避免重复点击；失败/超时场景沿用现有文案。

## FE-S2 全量对账保护

- 现状：`reconcileFolder` 对"本地文件存在但无 sync_state"直接判为 localChanged=false 并下载云端覆盖，可能覆盖用户本地新建/未同步文件。
- 要求：无 sync_state 且本地存在时视为"本地未同步"，保留本地（不覆盖），与 CREATE 分支冲突语义一致；若云端 md5 与本地 md5 不一致则走既有冲突策略（keep_both/local_wins/server_wins），不静默覆盖。

## 验收体验

- Web relay 上传中取消：任务从队列消失，服务端无残留 multipart/缓冲文件。
- 桌面对账遇到本地未同步文件：文件保留，日志提示，不覆盖。
