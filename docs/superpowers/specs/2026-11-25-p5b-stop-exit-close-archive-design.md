# P5-B 停止 / 退出 / 关闭 / 归档设计（GAP-05）

> 需求 FR-P-07（停止、退出流水线）、C16、FR-T-02（关闭变更项）、FR-T-05（退出任务/归档）、
> §4.8 级联表、J21（归档保留回滚入口）。前置：P1–P4、P5-A。

## 1. 语义

| 动作 | 对象 | 结果 | 级联 |
|---|---|---|---|
| 停止发布 `stop` | 发布单（生产流程） | 实例 `TERMINATED`、变更项 `TERMINATED` | 取消后续批次与在途引擎任务；**已成功批次保留**；作废待签审批（`CANCELED`）；释放单元锁；审计+通知 |
| 退出流水线 `exit` | 发布单（任意非终态） | 实例 `TERMINATED`、变更项 `TERMINATED` | 取消在途引擎任务；未跑完检查置 `INVALID`；作废待签审批；释放锁；审计+通知 |
| 关闭变更项 `close` | 变更项 | `CLOSED` | 终止其活动实例；释放锁；作废待签审批；作废未完成检查；审计+通知 |
| 退出任务 `archive` | 任务 | `ARCHIVED`（只读） | 级联关闭所有未完成变更项（含上面的级联）；取消在途实例；释放全部锁；**保留发布记录与回滚入口**；审计+通知 |

- **停止只发生在生产流程**（`SUBMITTED/PENDING_APPROVAL/READY/PROD_DEPLOYING`）；其余状态用「退出」。
  两者都作废待签审批（没有人需要再为一个已终止的发布签字）；停止**保留已记录检查**，
  退出/关闭才把未完成检查置 `INVALID`。
- **退出** 对任何非终态变更项可用（含 `TESTING/GATE_PENDING/REJECTED/GATE_FAILED/FAILED`）。
- **关闭** 对任何非终态可用；`CLOSED` 后不可再发布（状态机已保证）。
- **归档** 后任务只读：新增变更项、发布、日常、重跑一律 40001；回滚仍可用（运维按 5.7 发起）。
- 四个动作都**幂等**：对已处于终态的对象重复调用返回 40001（不静默成功）。
- 所有动作在**一个业务事务**里改业务状态，事务提交后做 best-effort 引擎取消与锁释放（与 orchestrator
  的终止释放同款：DB 删除不能回滚已发生的解锁）。

## 2. 终止引擎（新服务）

`PipelineTerminationService`（application）：

1. 取该发布单的**活动实例**：订单下 `PENDING/RUNNING/PAUSED` 的最新一条（同一时刻至多一条在线实例）。
2. CAS 终止：`PENDING/RUNNING/PAUSED → TERMINATED`（`status_reason` 写原因，`end_at=now`）。
   迁移不需要：列已存在；只在仓储加方法 `terminateInstance(instanceId, reason, now)`。
3. 未完成的节点/任务：当前节点 `RUNNING → SKIPPED`（finish 为空），`PENDING` 任务 → `SKIPPED`
   （summary「已取消：+原因」）；`WAITING_EXTERNAL/RUNNING` 任务先记 `external_id` 待取消，再置
   `SKIPPED`。**已 `SUCCESS` 的行原样保留**（停止不回收已上线批次）。
4. 事务提交后：对每个 `external_id` best-effort `CicdEngine.cancel(...)`（失败只 WARN），
   再 `UnitLease.release(unitId, instanceId)`（所有权校验，失败只 WARN）。
5. 不发 `InstanceFinishedEvent`？——要发：终止也是一次结束，监听方（回滚完成收敛、通知）需要知道。
   事件用 `InstanceStatus.TERMINATED`，并保持在业务事务内（与现有 finish 一致）。

## 3. 审批与检查作废

- `ApprovalService.cancelPending(orderId, snapshotId, reason)`：最新快照链上所有 `PENDING` 步骤
  → `CANCELED`（`ApprovalChain` 增加 `cancelPending`，`decided(CANCELED, null, 原因, now)`）。
- `GateService.invalidateUnfinished(itemId, reason)`：该变更项 `gate_check` 中 `PENDING/RUNNING`
  → `INVALID`；`release_item.gate_status` 置 `CHECK_INVALID`。已 `PASS/FAIL` 的历史不动。
- 二者都由 `PipelineTerminationService` 的调用方在**同一事务**里调用；失败不阻断（记 error 日志，
  下轮 gate 重算/审批读取会自然收敛）。

## 4. 服务与接口

`ReleaseControlService`（application）：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/v1/releases/{publishNo}/stop` | `control:execute-prod` + 单元范围 | 仅生产流程；原因必填（≤200） |
| POST | `/api/v1/releases/{publishNo}/exit` | `release:publish`（提交人/owner）或 `control:execute-prod` + 范围 | 任意非终态；原因必填 |
| POST | `/api/v1/items/{itemId}/close` | `release:publish` + 范围 | 任意非终态；原因必填 |
| POST | `/api/v1/tasks/{taskId}/archive` | `task:create` + 范围 | 任务创建人或 ADMIN；级联关闭 |

- 请求体统一 `{reason}`；响应 `{status, itemStatus, terminatedPipelineNo?}`（回显最新状态）。
- 错误：不存在 40000；状态不允许 40001（附 `currentStatus`）；越权 40009。
- `archive` 返回 `{taskStatus, closedItems, terminatedInstances}` 便于页面提示。

## 5. 审计与通知

- 审计：`STOP`（新增动作「停止发布」，默认 ORDER）、`EXIT`、`CLOSE`（默认 ORDER，显式 ITEM）、
  `ARCHIVE`（默认 ORDER，显式 TASK）；`objectId` 分别 publishNo/itemId/taskId，
  `scope_unit_id`/`scope_subsystem_id` 能取就填，reason 原样落 `audit_log.reason`。
- 通知（新增 4 个 event key，`NotificationEventKey` 追加）：
  - `RELEASE_STOPPED`、`RELEASE_EXITED`：接收人 = 任务成员 OPS/DEV + 创建人；
  - `ITEM_CLOSED`：任务成员 DEV/TEST/OPS + 创建人；
  - `TASK_ARCHIVED`：任务全部成员 + 系统负责人兜底。
  - 标题含对象号，正文含原因与「已上线批次保留」提示（stop）。
- 通知/审计失败都不得回滚业务（沿用既有 REQUIRES_NEW + 吞异常）。

## 6. 前端

- 发布内容页页头：「停止发布」（仅生产流程，`control:execute-prod`）与「退出流水线」，
  二次确认 + 原因输入（必填），成功后刷新并提示已保留批次。
- 变更项页：「关闭」按钮（确认 + 原因）。
- 任务详情页：「归档任务」按钮（强确认：展示将关闭 N 个未完成变更项、终止 M 个在途实例），
  归档后页面只读（隐藏新增/发布/日常/重跑按钮），保留发布记录入口。
- 亮/暗双主题；`scripts/verify-p5b-ui.mjs` + 截图 `release-stop-{light,dark}.png`、
  `task-archived-{light,dark}.png`。
- 演示：`scripts/demo-control.sql` 造 1 个可停止/退出的发布单与 1 个带未完成变更项的任务（幂等、demo 标记）。

## 7. 测试口径

1. stop：READY（实例 PAUSED）→ 实例/变更项 TERMINATED、锁释放、已成功批次保留；
   PROD_DEPLOYING 中途 → 后续批次 SKIPPED、引擎 cancel 被调用（fake engine）、失败不影响终止。
2. exit：TESTING / GATE_PENDING / PAUSED 各一条 → TERMINATED；待签审批 CANCELED；未完成检查 INVALID；
   活动实例终止；锁释放。
3. close：GATE_PENDING + 审批中 → CLOSED；重复 close → 40001；已 DONE 的项不可 close。
4. archive：任务下 1 个 DONE + 1 个 READY → DONE 不变、READY → CLOSED；任务 ARCHIVED；
   归档后新增变更项/发布/日常/重跑 → 40001；发布记录与回滚版本仍可读；锁全部释放。
5. 幂等/非法：对终态重复 stop/exit/close → 40001；LIB 项 close 允许、stop/exit 不适用。
6. 权限与范围：运维不能 exit（无 `release:publish`，除非有 `control:execute-prod`）、开发不能 stop、
   越界 40009。
7. 审计/通知：四个动作各一行审计、一条站内通知；失败隔离用临时改表名模拟。
8. ArchUnit：web 不 import 枚举；controller 测试不在 `..web..`。

## 8. 取舍

- **停止不回收已上线批次**（J8/J21）：只终止后续批次；镜像回滚是 P4-A 的独立动作。
- **归档不清空历史**：实例/任务/发布记录全部保留，只把任务置只读。
- **归档不阻止回滚**：回滚按 5.7 由运维发起；归档任务下的单元仍可回滚（记录保留）。
- **退出 vs 停止实现共用**终止路径，差别只在权限、允许状态与文案。
- 终止事件会让 `RollbackCompletionService` 把对应回滚记录收敛为 FAILED（若终止的是回滚实例），
  符合「回滚失败」语义；本阶段测试覆盖。