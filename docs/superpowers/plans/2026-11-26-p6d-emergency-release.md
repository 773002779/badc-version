# P6-D 紧急放行实现计划

契约：`docs/superpowers/specs/2026-11-26-p6d-emergency-release-design.md`。

## Task 1 — 后端

1. V32：`release_order` 四个 emergency 列 + `emergency_release` 历史表。
2. `EmergencyReleaseService`（ADMIN 角色校验、状态校验、作废 PENDING 审批、状态机推进、订单标记、
   审计 `EMERGENCY_RELEASE`、通知 `EMERGENCY_RELEASE`）；`AuditAction`/`NotificationEventKey` 追加。
3. `POST /api/v1/releases/{publishNo}/emergency-release` + DTO；发布详情/记录列表回显四个字段。
4. `ProductionBoundaryProbeService`：带 emergency 标记的订单跳过封板拦截（其余复核照旧）。
5. 测试 `server/src/test/java/com/sf/release/emergency/`：设计 §5 全条（真实库；冻结场景覆盖）。

硬约束：仅 ADMIN；不绕过门禁/镜像校验；不可撤销；审计/通知失败不阻断；全量测试绿（基线 937）；
提交一次 `feat: P6-D emergency release backend`。

## Task 2 — 前端

发布页红色「紧急放行」+ 确认/理由 + 已放行横幅；记录页红色 chip；探针 + 截图；
`npm run build` 绿。

## Task 3 — 文档

技术方案 §8.16 + §12.2.13；前端设计 §21；未实现清单 GAP-11 完成 + 取舍；需求规格 §10；台账。

## 门禁

Task 1 → lead 评审（ADMIN 窄权限、状态矩阵、审批作废、封板跳过范围、重复调用）→ Task 2 → 联调 → Task 3。