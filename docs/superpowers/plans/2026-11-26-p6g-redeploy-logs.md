# P6-G 单 DE 重部署与日志实现计划

契约：`docs/superpowers/specs/2026-11-26-p6g-redeploy-logs-design.md`。

## Task 1 — 后端

1. `SingleDeRedeployService`（资格校验、锁、基于现有 `PipelineFactory.createRerun` 构造只含目标批次的实例、
   `EXECUTE_RERUN`、检查 `INVALID`、审计 `RERUN`、启动）。
2. `TaskLogService` + 端点（kind 映射、offset/limit、引擎调用、错误映射、scope）。
3. Controller：`POST /releases/{publishNo}/tasks/{taskId}/redeploy`、
   `GET /releases/{publishNo}/tasks/{taskId}/logs`；DTO。
4. 测试 `server/src/test/java/com/sf/release/redeploy/`：设计 §4 的 1–3、5（fake engine）。

硬约束：不新增状态/事件；历史实例只读；成功任务拒绝；全量测试绿（基线以当前 HEAD 为准）；
提交一次 `feat: P6-G single-DE redeploy and logs backend`。

## Task 2 — 前端

发布页任务行「重新部署/日志」+ 条件禁用 + 确认 + 日志抽屉；探针 + 截图；`npm run build` 绿。

## Task 3 — 文档

技术方案 §8.19 + §12.2.16；前端设计 §24；未实现清单 PART-04 标完成 + 未做；需求规格 §10；台账。

## 门禁

Task 1 → lead 评审（资格、同批重试、检查作废、日志 kind/范围）→ Task 2 → 联调 → Task 3。