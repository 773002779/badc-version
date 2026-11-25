# P6-E 审批时效提醒实现计划

契约：`docs/superpowers/specs/2026-11-26-p6e-approval-reminder-design.md`。

## Task 1 — 后端

1. V33：`app_setting` 增加 `APPROVAL` 段种子（无新表）。
2. `application/reminder/ApprovalReminderService` + `infra/repo/ApprovalReminderMapper`
   （最新快照 PENDING 步骤、等待时长、系统负责人、ADMIN 用户）。
3. `SettingsService` 增加 `approvalSla()` / `saveApprovalSla()`（校验、审计）；
   `SettingsController` 增加 `GET/PUT /api/v1/settings/approval`。
4. `NotificationEventKey` 追加 `APPROVAL_REMIND`/`APPROVAL_ESCALATE`；接收人用
   `SettingsService.approvers()` 回退 `user_role`，升级 = ADMIN + 系统负责人。
5. `infra/job/ApprovalReminderJob`（10min，`release.approval.reminder-enabled` 默认 true，test 关闭）
   + `JobConfig` 装配。
6. 测试 `server/src/test/java/com/sf/release/reminder/`：设计 §4 全条（固定时钟；真实库）。

硬约束：只扫最新快照；升级后抑制提醒；dedupe 用通知唯一键；不发审计；读配置失败回退默认；
全量测试绿（基线 964）；提交一次 `feat: P6-E approval reminders backend`。

## Task 2 — 前端

设置页「审批时效」块（开关 + 两个小时数 + 保存/回显/只读）；探针 + 截图；`npm run build` 绿。

## Task 3 — 文档

技术方案 §8.17 + §12.2.14；前端设计 §22；未实现清单 GAP-12 完成 + 残留；需求规格 §10；台账。

## 门禁

Task 1 → lead 评审（阈值边界、只扫最新、升级抑制、接收人回退）→ Task 2 → 联调（构造 12h/24h 数据跑
`scan`）→ Task 3。