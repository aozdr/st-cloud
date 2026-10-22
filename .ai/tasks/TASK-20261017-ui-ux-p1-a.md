# TASK：TASK-20261017-ui-ux-p1-a 文件页信息架构与操作可发现性

> 依据：`.ai/docs/20261017-ui-ux-p1-a/design.md`。
> P1-A 是 P0-A/P0-B 之后的第三个切片；只改文件页，不做全站次级页与 PWA。

## 元信息

- Task ID：`20261017-ui-ux-p1-a`
- 关联 State：`.ai/state/20261017-ui-ux-p1-a.yaml`
- 归属 Agent：executor（taskType=implement）
- 日期：2026-10-17

## 目标

在不改变文件操作/API/权限语义的前提下，优化文件页的信息层级与操作效率：

1. 页面标题 + 路径 + 工具栏恢复“先位置、后操作”的层级；
2. 上传成为主操作，新建降为次操作；
3. 移动端工具栏收敛，避免右侧控件被裁切；
4. 行内收藏/更多在触屏可见，关键操作命中区 ≥36px；
5. 分页/面包屑等高频控件补齐触控目标。

## 修改范围（include）

- `st-web/src/components/file/FileBrowser.tsx`
- `st-web/src/components/file/FileToolbar.tsx`
- `st-web/src/components/file/FileTableView.tsx`
- `st-web/src/components/file/FileGrid.tsx`
- `st-web/src/components/file/FileBreadcrumb.tsx`
- `st-web/src/components/file/FileBrowserPagination.tsx`
- 任务文档与验证

## 禁止修改范围（exclude）

- 不改后端/数据库/桌面端/上传下载/同步协议。
- 不改文件操作逻辑、权限判断、虚拟列表算法。
- 不做 P1-B 表格 grid ARIA、P1-C 导航收敛、P1-D PWA 品牌。
- 不新增无实际能力的“筛选”按钮。

## 验收标准

- [ ] 文件页显示明确标题：根目录“全部文件”、文件夹当前名、分类名。
- [ ] 路径面包屑位于工具栏之前；分类页不显示无关面包屑。
- [ ] 上传为 primary，新建为 secondary；移动端工具栏无被裁切控件。
- [ ] 表格行收藏/更多在 `<md` 常显；按钮命中区 ≥36×36。
- [ ] 分页上一页/下一页命中区 ≥36px；面包屑路径编辑按钮 ≥32px。
- [ ] 390px `/files` pageOverflow=0、tableOverflow=0。
- [ ] `npm run lint`、`npm run build` 通过；`git diff --check` 通过。
- [ ] 真实浏览器 1440/390 截图与交互检查通过。

## 测试要求

- `npm run lint`、`npm run build`。
- 浏览器：登录后 `/files` 根目录、文件夹、分类；1440 + 390；检查标题/面包屑/工具栏、行操作可见性、无横向溢出。
- 静态扫描：新增标题、上传 primary、移动工具栏隐藏类。
- 无后端时记录限制，不声称已完成视觉验收。

## 输出

`.ai/docs/20261017-ui-ux-p1-a/verification.md`。
