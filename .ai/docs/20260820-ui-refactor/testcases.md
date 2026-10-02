# 测试用例（20260820-ui-refactor）

## 验收点

| # | 用例 | 预期 | 结果 |
|---|------|------|------|
| 1 | npm run build | 通过 | PASS |
| 2 | npm run lint | 0 error | PASS（9 条既有 warning 保留） |
| 3 | dev server 冒烟 /、/login、/src/main.tsx | 200 | PASS |
| 4 | 侧栏宽度/底色/边框 | 240px / #FFF / #E8EBF1 | PASS |
| 5 | 顶栏高度/边框 | 68px / #EEF0F4 | PASS |
| 6 | 搜索框 | 320×40、#F4F6FA、r10 | PASS |
| 7 | 主按钮 | #4F6EF7、40px、r9 | PASS |
| 8 | 文件列表容器 | r14、#FFF、border #E8EBF1 | PASS |
| 9 | 表头 | 44px、12px、#929AAA、底 #FCFCFD | PASS |
| 10 | 行 | 64px、hover #F8FAFF、selected #F1F4FF | PASS |
| 11 | 文件名 | 14px / 500 / #1F2430 | PASS |
| 12 | 复选框 | 16×16 r4 | PASS |
| 13 | 右键菜单 | 180px、r10、shadow-md、项 36px r7 | PASS |
| 14 | 网格卡 | r14、p16、#FFF、border #E8EBF1 | PASS |
| 15 | 弹窗 | 480px、r16、shadow-lg、Escape 关闭 | PASS |
| 16 | 三视图收敛 | 仅 list/grid，默认 list，旧值兼容映射 | PASS（代码审查） |
| 17 | 深色模式 | dark 类可切换，新 token 有 dark 变体 | PASS（代码审查） |
| 18 | 业务回归 | 多选/拖拽/快捷键/URL 同步 props 不变 | PASS（代码审查） |
