# TASK-20260815-new-file-02：新建文件（前端）

## 目标

实现前端「新建」菜单与交互：工具栏下拉 + 右键菜单，新建后 Office 文件自动跳转编辑。

## 修改范围

### st-web

- `FileToolbar.tsx`：新增「新建」下拉菜单（新建文件夹 / 文本文档 / Word 文档 / Excel 表格 / PPT 演示）（D2）
- `ContextMenu.tsx`：空白区域右键菜单整合「新建」子菜单（D2）
- `lib/`：新增 `createBlankFile(parentId, type)` API 封装
- `types`：新建请求/响应类型
- 交互：
  - 调接口成功 → 刷新列表；docx/xlsx/pptx 跳转 `/file/:nodeId/editor`（带来源路径，P3）；txt 留在列表
  - 失败（配额/权限）→ toast 提示，不跳转
- 权限显隐：个人目录与团队 upload 目录显示新建菜单；无权限隐藏（D3 由后端把关，前端按权限码展示）

## 禁止修改范围

- st-*/src/main/java、st-desktop、docker、.ai/**

## 验收标准

- TC-11~TC-13 覆盖（菜单显隐/跳转/错误提示）
- `npx tsc --noEmit` 通过；`npm run build` 由主线程统一验证

## 验证命令

```bash
cd st-web && npx tsc --noEmit
```

## 输出要求

- 返回 State Delta（改动文件、验收对照、风险）
