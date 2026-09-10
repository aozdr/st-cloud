# 测试用例：历史版本预览

> Task: `20260910-version-preview`｜产出：tester｜关联设计：`.ai/docs/20260910-version-preview/design.md`
> 环境：st-preview 集成测试（H2 + 真实 Mapper，S3/Storage/FileService Mock）；st-web 构建校验

## 一、后端集成测试（st-preview）

| 编号 | 场景 | 前置 | 步骤 | 预期 |
|------|------|------|------|------|
| TC-01 | 文本历史版本预览返回该版本内容 | 节点 `note.txt` 当前对象 `cur/note.txt`；版本 V1 对象 `ver/note-v1.txt` 内容 "old content" | 调 `previewVersion(nodeId, versionId)` | `type=text`，`content="old content"`；`downloadObject` 以版本路径调用 |
| TC-02 | 视频历史版本返回版本预签名 URL | 节点 `movie.mp4`；版本对象 `ver/movie-v1.mp4` | 调 `previewVersion` | `type=video`，`url` 来自版本路径，与当前对象路径不同 |
| TC-03 | 图片历史版本缩略图 key 含版本号 | 节点 `pic.png`；版本 V2 | 调 `previewVersion`，捕获 presign 请求 | 请求 key 以 `thumbnails/{nodeId}/v2/lg.jpg` 结尾，不覆盖当前版本 key |
| TC-04 | 版本不属于该文件 | 节点 A、节点 B 均存在；版本记录挂在 B | 用 A 的 nodeId + B 的 versionId 调用 | 抛 `FILE_NOT_FOUND`，文案「版本不存在」 |
| TC-05 | versionId 不存在 | 节点存在 | 用不存在的 versionId 调用 | 抛 `FILE_NOT_FOUND`，文案「版本不存在」 |
| TC-06 | 节点无访问权限 | 节点存在；`validateAccessible` 抛无权限异常 | 调 `previewVersion` | 抛出同一无权限异常，不返回预览结果 |
| TC-07 | Office 历史版本 | 节点 `doc.docx`；版本存在 | 调 `previewVersion` | `type=unsupported`，`suffix=docx` |
| TC-08 | 当前版本预览回归 | 节点 `note.txt` 当前对象有内容 | 调 `preview(nodeId)` | 行为不变：`type=text`，读取当前对象路径 |
| TC-09 | 文件夹节点拒绝预览 | `node_type=0` 的节点 | 调 `previewVersion` | 抛 `BAD_REQUEST`「文件夹不支持预览」 |
| TC-10 | Office 历史版本编辑器配置只读 | 节点存在、版本 V7 属于该节点 | 调 `generateVersionConfig(100, 7)` | `document.key=100_v7`、`permissions.edit=false`、`editorConfig.mode=view`、无 `callbackUrl` |
| TC-11 | 版本不属于该节点 | 版本记录挂在他节点 | 调 `generateVersionConfig` | 抛 `FILE_NOT_FOUND`「版本不存在」 |

## 二、前端用例

| 编号 | 场景 | 步骤 | 预期 |
|------|------|------|------|
| FE-01 | 预览入口范围 | 打开历史版本弹窗 | 非当前版本行显示「预览」；当前版本行不显示 |
| FE-02 | 打开版本预览 | 点某历史版本「预览」 | 打开预览层，显示「历史版本 V{n}」 |
| FE-03 | Office 历史版本 | 对 `docx` 历史版本点预览 | 提示「该类型历史版本暂不支持在线预览」，不跳转编辑器路由 |
| FE-04 | 版本模式控件 | 版本预览层内 | 左右切换、幻灯片、下载按钮不可用 |
| FE-05 | 构建校验 | `npm run build` | 通过（tsc + vite） |
| FE-06 | Office 历史版本预览 | 对 docx/xlsx/pptx 历史版本点「预览」 | 跳转 `/file/{id}/editor?mode=view&versionId=...`，OnlyOffice 只读打开该版本，界面无保存入口 |

## 三、手工验收

| 编号 | 验证点 | 预期 |
|------|--------|------|
| M-01 | 预览历史版本后当前版本 | 当前版本内容、大小、修改时间不变 |
| M-02 | 最近文件 | 版本预览不写入「最近文件」 |
| M-03 | 弹窗层级 | 版本预览层在历史版本弹窗之上，可关闭返回继续操作 |
| M-04 | 旧接口回归 | `/api/preview/{nodeId}`、`/thumbnail`、`/video` 路径与返回结构不变 |

## 四、验证命令

```powershell
mvn -pl st-preview -am test
cd st-web; npm run build
```
