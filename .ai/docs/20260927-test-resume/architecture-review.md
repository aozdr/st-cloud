# 技术方案核对
复核实际 src/sync-engine.ts、sync-reconcile.ts、restart-worker.cjs 与原始 CR-04 用例，既有架构可扩展，无需替换算法或新依赖。保持真实 SQLite 和跨进程证据，API替身仅证明客户端；服务端证据分别记录。没有新增兼容性裁决。
