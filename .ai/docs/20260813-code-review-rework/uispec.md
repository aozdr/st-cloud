# UI 说明：上传队列取消操作

- UploadPanel 任务项 X 按钮：uploading（relay）状态点击 → 任务状态置 `cancelling` → 调 abort API → 移除任务；失败则置 failed 并显示错误。
- 不新增页面/路由，仅调整 UploadTaskStatus 枚举（增加 `cancelling`）与 UploadPanel 交互。
- TransferManager 桌面端复用 electron cancelUpload，行为不变。
