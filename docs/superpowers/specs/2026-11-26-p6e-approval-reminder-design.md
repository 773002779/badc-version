# P6-E 审批时效提醒设计（GAP-12）

> 需求 J7 / FR-G-04：审批 **12h 未处理提醒、24h 升级**，可配阈值；本切片做一个定时扫描 + 通知，
> 复用 P4-B 的站内/企微通道。

## 1. 语义

- 扫描对象：**最新快照**（每单 `MAX(snapshot_id)`）中 `status='PENDING'` 的审批步骤，且：
  - 发布单未终结（item 状态 `PENDING_APPROVAL`；`READY/REJECTED/…` 不再提醒）；
  - 等待时长 = `now - approval.created_at`。
- 两级：
  1. `>= remindHours`（默认 12）→ `APPROVAL_REMIND`，接收人 = 该步骤角色的审批人（P6-B 设置的生效名单；
     名单为空回退 `user_role`）；
  2. `>= escalateHours`（默认 24，必须 > remindHours）→ `APPROVAL_ESCALATE`，接收人 = **ADMIN 用户 +
     该单系统负责人**，正文点名当前待签角色与已等待时长。升级后**不再重复发提醒**（升级已含提醒语义）。
- 去重：`notification.dedupe_key` 的 ref = `approval:{snapshotId}:{stepNo}:REMIND|ESCALATE`，
  同一代同一步骤只发一次；跨扫描幂等。
- 定时任务：`ApprovalReminderJob`，`fixedDelay = 10min`，开关 `release.approval.reminder-enabled`
  （默认 true；test profile 关闭，测试直接调 service）。

## 2. 配置（V33）

在 P6-B 的 `app_setting` 增加一段（无新表）：

```sql
INSERT INTO app_setting (setting_key, value_json, updated_at)
VALUES ('APPROVAL', '{"enabled":true,"remindHours":12,"escalateHours":24}', NOW(3));
```

- 校验：`remindHours` 1–72；`escalateHours` 2–168 且 **> remindHours**；`enabled=false` 时任务不发任何消息。
- `GET/PUT /api/v1/settings/approval`（`setting:manage`；审计 `SETTING_UPDATE` object `APPROVAL`）；
  设置页新增「审批时效」块（开关 + 两个小时数）。

## 3. 服务与任务

- `ApprovalReminderService.scan(Instant now, int limit)`（可测）：
  1. 读配置（读失败回退默认 12/24，`enabled` 回退 true）；
  2. `ApprovalReminderMapper.selectDue(now - remind, limit)` 返回待签步骤行
     （snapshotId、stepNo、orderId、publishNo、role、waitingSince、subsystemId、creator 等）；
  3. 对每行：已等待 ≥ escalate → 发升级（若 dedupe 未命中）；否则 ≥ remind → 发提醒；
  4. 每条发送前后都不抛出：`NotificationService.notify` 自身吞异常；扫描异常记日志并返回本轮计数。
- 接收人解析：
  - 提醒：`SettingsService.approvers()` 的该角色名单（空→`user_role` 该角色）；无任何人 → 系统负责人兜底
    （与 P4-B 的审批寻址一致）。
  - 升级：`user_role` 中 `ADMIN` 的用户 + 系统负责人（去重）。
- 新事件键：`APPROVAL_REMIND`（「审批待处理提醒」）、`APPROVAL_ESCALATE`（「审批超时升级」），对象 ORDER。
- 通知正文：发布单号、当前待签角色中文名、已等待小时数、直达链接提示（`/releases/{publishNo}`）。
- 不发审计（FR-N-02 未列提醒；通知行本身即留痕）。

## 4. 测试口径

1. 阈值：等待 11h 不发；12h 发提醒；23h 仍只提醒（已发过不再发）；24h 发升级且之后不再发提醒。
2. 配置：remind=2/escalate=4 → 3h 提醒、5h 升级；`enabled=false` 全不发；非法阈值 PUT 40000。
3. 只扫最新快照：旧 snapshot 的 PENDING 行不发；`CANCELED/APPROVED` 不发；item 已 READY/REJECTED 不发。
4. 去重：连续两次 `scan` 同一时刻只各发一条（通知唯一键）。
5. 接收人：生效名单命中；名单空回退 `user_role`；角色无人 → 系统负责人；升级 = ADMIN + 系统负责人。
6. 升级后的提醒抑制：先到 12h 发提醒，24h 发升级；第三次扫描无新增。
7. 任务开关：job 的 `enabled` 配置关闭时不注册（`@ConditionalOnProperty` 沿用现有 job 写法）。
8. ArchUnit/包规则；controller 测试不在 `..web..`。

## 5. 取舍

- **只提醒「最新快照」**：历史代的未签步骤是死行（驳回/重提会作废），提醒它们只会制造噪音。
- **提醒一次、升级一次**：不做每日重复提醒（需求只写 12h/24h 两个点）；要重复提醒需新的去重维度。
- **升级对象是 ADMIN + 系统负责人**：Q2（审批人名单来源）未定时，这是唯一稳定的「更高一层」；
  未来可配置升级组。
- **不发审计**：提醒是通道行为，通知表已有全部记录；`SETTING_UPDATE` 审计覆盖阈值修改。
- 站内 + 企微都走 P4-B 通道；企微失败重试一次、不阻塞扫描。