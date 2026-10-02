# Code Review（20260820-ui-refactor）

## 审查范围

26 个改动文件 + 1 删除文件，全部为展示层。

## 发现与修复

| 级别 | 问题 | 修复 |
|------|------|------|
| P1 | 文件弹窗 384px，规范 Modal 480px | Dialogs×3 / Convert / Move 改为 w-[480px] |
| P2 | 文件名非选中态 400 字重，规范 500 | FileTableView 名称加 font-medium |

## 通过项

- 无硬编码全局颜色残留（类型图标按规范使用语义色）。
- 无新增依赖；无越界文件改动（后端/DB/接口零改动）。
- 删除 FileTable.tsx 后无悬挂引用，tsc -b 通过。
- localStorage fileView 旧值兼容映射（table/card 归入 list）。

## 结论

通过，无 P0。
