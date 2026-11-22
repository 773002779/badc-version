# P3 · 生产发布管控与执行 — 设计（2026-11-22）

## 1. 为什么做

P2 之后流水线停在 `PROD_DEPLOY`：节点没有处理器，实例 `PAUSED`，变更项停在「待生产准入」。
`ItemStateMachine` 里 `SUBMIT / NEED_APPROVAL / NO_APPROVAL / APPROVE_ALL / REJECT / EXECUTE /
FREEZE_HIT / PROD_OK / PROD_FAIL` 九条边早已存在，但驱动它们的东西一件都没有：
发布窗口、封板、OA 工单、分级审批、准入快照、执行前复核、生产部署处理器。

这一轮把 5.6 的主链路接通：**提交生产 → 准入判定 → 审批（或免审）→ 待执行 → 运维执行 →
分批串行部署 → 完成/失败**。

## 2. 目标 / 非目标

**目标**

1. 发布窗口：全局默认 + 系统覆盖；星期规则 + 例外日历；跨零点；返回「是否在窗口内」与
   「下一个窗口」；判定以提交人填的**计划生产时间**为准。
2. 封板：全局/指定系统、起止时间、事由；生效时在途单（`SUBMITTED/PENDING_APPROVAL/READY`）
   转双签；解除后重算准入、保留已完成的会签。
3. OA 工单：提交时必填、格式校验、绑定发布单（重跑复用）。
4. 分级审批：窗口内且未封板 → 免审；窗口外 → 运维组长单签；封板期 → 运维组长 + 部门经理双签，
   顺序会签，禁止自审，驳回终止，可重提。
5. 准入快照：不可变、可追溯；重提生成新快照。
6. 执行：仅运维；执行前复核封板 / 豁免有效期 / 镜像 digest，不重算窗口；按批次升序串行，
   批次内并行，某批失败即停后续批次；批次边界重新检查封板与门禁失效，命中则暂停。
7. 页面：提交生产对话框、审批区、封板横幅与管理、窗口配置、执行按钮与批次进度。

**非目标（记录为后续）**

- 企微/站内通知、待办中心、操作日志页（通知能力单独一轮）。
- 紧急放行（管理员红色留痕放行）。
- 单 DE / 单批次重试入口；失败后仍走整单重跑（P2 已有）。
- 封板生效时 `PROD_DEPLOYING` 的「继续/停止」交互：本轮在批次边界暂停，用「恢复执行」继续；
  停止发布（把变更项置终止）下一轮。
- OA 系统存在性/状态校验（只存号 + 格式校验，接口预留）。
- 审批超时 12h/24h 提醒（字段留痕，不发通知）。
- 每系统一条 CODING 连接（仍只有 GLOBAL）。

## 3. 方案与取舍

- **窗口用规则表，不用 cron 表达式**：星期 + 起止时间 + 日期例外，管理员能在页面上填出来；
  cron 能表达同样的东西，但「工作日 22:00–24:00」在页面上会变成一行没人敢改的字符串。
- **审批按角色步骤，不指定具体人**：一个步骤 = 一个角色（`OPS_LEAD` / `DEPT_MANAGER`），
  拥有该角色的任何人可签；指定人需要代理表与人员变更维护，一期不引入。
- **快照落表 + 关键字段单列、清单字段 JSON**：需要被查询/比对的是窗口、封板、digest、结论；
  审批链和外键清单用 JSON，避免为了历史结构再建三张表。
- **执行前复核只比对变化项**：窗口按提交时的时间算过一遍并写进快照，执行时重算窗口会让
  「计划时间在窗口内、实际执行晚了」变成另一种判定，需求 J5 明确不重算。
- **生产部署处理器按批次推进**：`start()` 只触发当前最低未完成批次，批内并行；
  `onTaskCompleted` 在整批成功后触发下一批——失败自然停住后续批次，不需要一个「停止」开关。

## 4. 决定的细节

### 4.1 窗口

- 表：`release_window(scope, subsystem_id, enabled, timezone)` + `window_rule(day_of_week,
  start_time, end_time)` + `window_exception(the_date, mode OPEN|CLOSED, start_time, end_time)`。
- **未配置**（没有任何窗口行）视为**不在窗口内**：不允许「没配窗口」变成「永远免审批」。
- **`enabled=false` 是关掉约束**，语义定为「始终在窗口内」（即使没有规则），页面对此明示：
  「已关闭窗口限制 ⇒ 提交即免审」。两者容易混为一谈，页面上分开写。
- 判定：例外优先（CLOSED 整天关；OPEN 且带时段则只开该段；OPEN 不带时段＝整天开）；
  否则按星期规则。跨零点 `22:00–02:00` 覆盖当天 22:00–23:59 与**次日** 00:00–02:00；
  判断 `01:00` 这类时间时同时看「当天规则」和「前一天跨零点的尾巴」，不做日期归属拆分。
- `nextWindow`：从计划时间起按分钟向前找第一个「在窗口内」的点，最多找 7 天；时段过长/过短
  （<30 分钟或 >12 小时）在保存时给 WARN，不阻断。
- 判定结果写 `release_order.window_result`（`IN`/`OUT`）。

### 4.2 封板

- `freeze_event(scope GLOBAL|SUBSYSTEM, subsystem_id, reason, impact, initiator, start_at,
  end_at, status ACTIVE|RELEASED|EXPIRED, released_by, released_at)`。
- 「生效中」= `status=ACTIVE` 且 `start_at <= now < end_at`。
- 生效时在途处理：对受影响系统、状态在 `SUBMITTED/PENDING_APPROVAL/READY` 的发布单，
  保证审批链里有一个 `DEPT_MANAGER` 步骤（缺则追加到末尾），并把 `READY` 拉回
  `PENDING_APPROVAL`（事件 `FREEZE_HIT`）。已完成的会签保留。
- 解除：重算审批链——窗口内 → 免审；窗口外 → 只留 `OPS_LEAD`；封板期已过则移除
  尚未签的 `DEPT_MANAGER`。已签的步骤不删（留痕）。链已齐 → `APPROVE_ALL` → `READY`。
- 到期自动失效由每日扫描处理（`EXPIRED`），与豁免到期同一个 30s/每日任务里做。

### 4.3 OA 工单

- 提交时必填，格式 `^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$`（J6 的「格式校验」，具体规则可配）。
- 存在 `release_order.oa_ticket_no`，绑定发布单：重跑复用，不重填；多批次 DE 共用一张。
- 准入快照里存号码副本，供审计。

### 4.4 分级审批

- 链由快照决定：封板 → `OPS_LEAD` + `DEPT_MANAGER`；窗口外 → `OPS_LEAD`；否则空。
- 顺序会签：只有最低未决步骤可操作；通过后下一步变 PENDING；最后一步通过 →
  `APPROVE_ALL` → `READY`。
- 驳回：任一未决步骤可驳回 → 该单 `REJECTED`，其余步骤 `CANCELED`，必填意见。
- 自审：`actor == submitter` → 40008，不自动转交（没有代理表）。页面提示「请由其他审批人操作」。
- 重提：`REJECTED` → `SUBMITTED`（事件 `RESUBMIT`），重新走准入并生成新快照；旧审批单保留为历史。

### 4.5 准入与快照

提交时按固定顺序判定，**每一步失败都返回明确原因与下一步动作**：

1. 检查门禁（`GateService.decisionFor`）：`INVALID` / 非豁免 `BLOCK` → 40002，列缺项；
2. 测试门禁：`TEST_PASS` 才继续；
3. OA 工单：非空 + 格式；
4. 窗口：`IN` → 免审；`OUT` → 需要 `OPS_LEAD`；
5. 封板：生效中 → 再加 `DEPT_MANAGER`；
6. 计划时间：必须晚于当前时间，且不超过 30 天（防止「提交一个永远不执行的时间」）；
7. 写快照 + 订单字段（OA、计划时间、window_result、deadline=计划时间+2h）+ 变更项状态。

快照字段：订单/变更项、计划时间、`inWindow`、`windowId`、`frozen`、`freezeIds`、最高等级、
检查结论、豁免 id 清单、测试结论与通过人、OA 号、digest、审批链快照。

**幂等**：只有 `GATE_PENDING` 的变更项可以提交；重复提交返回 40001（已是 SUBMITTED/READY）。

### 4.6 执行前复核

执行时只比对三件事（不重算窗口）：

- 封板：现在是否有生效封板；有且快照里没有 → 阻断（40003 `OUT_OF_WINDOW` 不用；用
  `GATE_BLOCKED` 并给原因「封板生效，需重新审批」），同时把变更项拉回 `PENDING_APPROVAL`
  并补步骤；
- 豁免：快照里的豁免必须仍是 `APPROVED` 且未过期，否则 40002「豁免已失效」；
- digest：实例镜像与快照 digest 不一致 → 40007，不可豁免（J27）。

复核结果写执行记录（`release_order.status` + 实例 `status_reason` 已有），页面展示。

### 4.7 执行与批次

- `POST /releases/{publishNo}/execute`：权限 `control:execute-prod`；要求变更项 `READY`、
  订单有最新快照、复核通过；写 `EXECUTE` 事件（`READY → PROD_DEPLOYING`）、把实例从
  `PAUSED` 恢复并推进。
- `PROD_DEPLOY` 处理器：节点任务已按 `deploy_batch_no` 预生成；`start()` 只触发**最低未完成批次**
  的全部 DE（批内并行、各自记 externalId、8 分钟 deadline）；某 DE 失败 → 该批不完成 →
  节点失败 → 后续批次不触发（要求 C14）。
- 批次边界：触发下一批之前检查（a）系统是否有生效封板（b）门禁是否 `INVALID`/非豁免 `BLOCK`；
  命中则把实例置 `PAUSED` 并写 `status_reason`，变更项保持 `PROD_DEPLOYING`；
  「恢复执行」可继续（复用 P2 的 resume，补上封板检查）。
- 节点成功 → `PROD_OK` → `DONE`；节点失败 → `PROD_FAIL` → `FAILED`（P1 的监听器已接好）。

### 4.8 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/windows?subsystemId=` | 登录 | 生效窗口 + 规则 + 例外 + 下一个窗口 |
| PUT | `/api/v1/windows` | `setting:manage` | 保存（scope GLOBAL/SUBSYSTEM） |
| POST | `/api/v1/windows/check` | 登录 | `{subsystemId, at}` → inWindow + next + 规则来源 |
| GET | `/api/v1/freezes?active=&subsystemId=` | 登录 | 封板列表 |
| POST | `/api/v1/freezes` | `control:freeze` | 新建（立即生效） |
| POST | `/api/v1/freezes/{id}/release` | `control:unfreeze` | 解除（原因） |
| POST | `/api/v1/releases/{publishNo}/submit-prod` | `control:submit-prod` | 提交生产 |
| GET | `/api/v1/releases/{publishNo}/admission` | 登录 + 单元范围 | 快照、审批链、复核预览 |
| POST | `/api/v1/releases/{publishNo}/approve` | `control:approve` | 通过当前步骤 |
| POST | `/api/v1/releases/{publishNo}/reject` | `control:approve` | 驳回（原因） |
| POST | `/api/v1/releases/{publishNo}/execute` | `control:execute-prod` | 执行生产 |
| POST | `/api/v1/releases/{publishNo}/resubmit` | `control:submit-prod` | 驳回后重提 |

### 4.9 数据表（V21）

`release_window` / `window_rule` / `window_exception` / `freeze_event` / `approval` /
`admission_snapshot`，字段按 4.1–4.5。`release_order.status` 复用，取值
`INIT / SUBMITTED / PENDING_APPROVAL / READY / EXECUTING / DONE / FAILED / REJECTED`。

## 5. 留给实现者

- 测试：窗口跨零点/例外/未配置、封板在途转双签、免审/单签/双签三条链、自审 40008、
  驳回与重提、快照不可变、复核三种失败、批次串行与失败停批、批次边界暂停。
- 文档：需求 5.6 标注落地、技术方案 8 章与 12.2.3、台账追加本轮裁决。
- 截图：提交对话框、审批区、封板横幅、窗口配置、批次执行中。