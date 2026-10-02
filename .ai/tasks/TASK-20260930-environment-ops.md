# O03 依赖运行保障

目标：从真实本机 Docker 收集当前状态、镜像身份、可保留故障证据；固定现有镜像，补可实际运行的探针，记录启动依赖检查与实际 Redis/MQ/S3/ES 证据。
include: docker/docker-compose.yml；docker/elasticsearch/Dockerfile；docker/.env.example；docker/scripts/*；.ai/docs/20260930-environment-remediation/ops-*；.ai/runtime/results/DISPATCH-env-ops-01.json。
exclude: State、产品源码、数据库迁移/数据、历史文档、用户资源、Git、Maven。不得停止/删除/重建现有容器，不得删除任何数据或桶，不要拉取新镜像。
先核对实装镜像 RepoDigests，固定当前验证镜像（优先仓库digest，fallback明确ID不可远程复现），保证已有环境变量兼容。healthcheck 用容器内实有工具试运行。主线程接下来启动新后端，避免资源争用；你只读运行容器并exec只读或独立测试消息/对象探针。测试资源专用随机名、保留审计，非用户资源。日志不输出凭据。历史OOM根因不可回溯时明确未知。
输出中文部分 IMPLEMENTED proposal env-code-r1，by=/root/ops_validation。主线程集成Evaluate。
