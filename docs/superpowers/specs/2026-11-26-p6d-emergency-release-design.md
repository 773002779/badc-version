# P6-D 紧急放行设计（GAP-11）

> 需求 J11：紧急放行**仅管理员**，必须填理由并红色高亮留痕；跳过准入/审批/窗口。
> 本阶段在「已有生产准入快照」的发布单上放行，不绕过门禁检查本身（见 §6 取舍）。

## 1. 语义

- 入口：发布单页「紧急放行」（仅 ADMIN 可见/可用；红色高亮 + 二次确认 + 理由必填 1–200）。
- 允许状态：`SUBMITTED`（已提交、待判定审批）、`PENDING_APPROVAL`（审批中）。
  - `READY` 已可执行 → 40001「已经是待执行状态，无需紧急放行」；
  - `GATE_PENDING/GATE_FAILED/FAILED/REJECTED/DONE/终态` → 40001，提示先走正常提交/重跑
    （没有准入快照时无法执行，见 §6）。
- 执行内容（一个事务）：
  1. 作废该发布单最新快照链上所有 `PENDING` 审批步骤（`CANCELED`，理由写明紧急放行）；
  2. 状态机：`PENDING_APPROVAL → APPROVE_ALL → READY`；`SUBMITTED → NO_APPROVAL → READY`；
  3. 写 `emergency_release` 记录（order、actor、reason、created_at）；
  4. 订单状态镜像 `READY` + `emergency_release=1` 标记（供列表/详情红色标识）；
  5. 审计 `EMERGENCY_RELEASE`（ORDER，before/after 状态、reason）；通知 `EMERGENCY_RELEASE`
     （接收人：任务成员 OPS/DEV + 创建人）。
- **执行时复核**：`ProductionBoundaryProbeService`（P3 执行前复核）对带紧急放行标记的发布单
  **跳过封板拦截**（不再要求封板双签/等待封板结束），其余复核（镜像一致性、门禁失效、豁免过期）照旧；
  复核结果仍写审计。窗口本来就在提交时判定、执行不重算（J5），无需额外处理。
- 标记保留到发布单终结（不做「撤销放行」；再发布走重跑）。

## 2. 模型（V32）

```sql
ALTER TABLE release_order
  ADD COLUMN emergency_release TINYINT NOT NULL DEFAULT 0 COMMENT '1 = released via J11',
  ADD COLUMN emergency_reason  VARCHAR(512) NULL,
  ADD COLUMN emergency_by      VARCHAR(32)  NULL,
  ADD COLUMN emergency_at      DATETIME(3)  NULL;

CREATE TABLE emergency_release (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id   BIGINT       NOT NULL,
  publish_no VARCHAR(32)  NOT NULL,
  reason     VARCHAR(512) NOT NULL,
  actor      VARCHAR(32)  NOT NULL,
  created_at DATETIME(3)  NOT NULL,
  KEY idx_emergency_order (order_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- 订单列是读取标记（列表/详情不用 join）；表是历史（允许同一单多次重跑后再次紧急放行）。
- 不改审批表结构：被作废的步骤就是 `CANCELED` 状态（既有语义）。

## 3. 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/v1/releases/{publishNo}/emergency-release` | **仅 ADMIN 角色**（不是权限点） | body `{reason}`；响应 `{status, itemStatus, emergency:true}` |

- 非 ADMIN → 40009（即使拥有 `control:approve` 也不行：C20/J11 只允许管理员）。
- 不存在 40000；状态不允许 40001（附 `currentStatus`）；理由 1–200 → 40000。
- 发布详情/列表行新增 `emergencyRelease/emergencyReason/emergencyBy/emergencyAt` 字符串字段。

## 4. 页面

- 发布内容页：
  - 页头「紧急放行」按钮：仅 ADMIN 显示，红色（`--danger`）；确认框含红色警示文案 + 理由输入；
  - 已放行时页头红色横幅：「已紧急放行 · 理由 · 操作人 · 时间」，并在准入面板标注「审批链已作废」。
- 发布记录页：行内红色「紧急放行」chip（hover 显示理由/操作人）。
- 亮/暗双主题；`scripts/verify-p6d-ui.mjs` + 截图 `emergency-{light,dark}.png`。

## 5. 测试口径

1. 权限：ADMIN 成功；非 ADMIN（含拥有 control:approve 的 OPS_LEAD）40009；未登录 40009。
2. 状态：PENDING_APPROVAL → READY 且审批步骤全部 CANCELED；SUBMITTED → READY；
   READY/DONE/GATE_PENDING/FAILED → 40001（附 currentStatus）；重复调用 40001。
3. 理由：空/超长 40000；订单标记四列写入；`emergency_release` 一行。
4. 执行复核：封板生效中，普通单被拦；紧急放行单**不被封板拦截**，仍检查镜像一致性/门禁失效；
   复核审计照旧。
5. 审计/通知：`EMERGENCY_RELEASE` 一行（before/after、reason、scope）；通知 OPS/DEV/创建人各一条。
6. 列表/详情：`emergencyRelease=true` 字段回显；记录页 chip 数据来源。
7. ArchUnit/包规则；controller 测试不在 `..web..`。

## 6. 取舍

- **不绕过门禁检查与快照**：没有准入快照就没有可执行对象；J11 的「跳过准入」实现为
  「跳过审批与封板等待」，门禁/镜像一致性仍执行（安全底线）。GATE_PENDING/REJECTED 需先走正常提交
  或重跑；文档明确这一点。
- **仅 ADMIN 角色**，不复用 `control:approve` 权限点：J11 要求更窄。
- **不可撤销**：标记与记录永久保留；再次发布需重跑，重跑后旧的 emergency 标记仍显示为历史
  （列表/详情按订单显示最近一次）。
- **窗口不重算**（J5 既有口径）；紧急放行只影响审批与封板拦截。
- 通知沿用 P4-B 通道（站内 + 企微开关），失败不阻断。