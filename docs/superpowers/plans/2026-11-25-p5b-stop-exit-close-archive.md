# P5-B 停止/退出/关闭/归档实现计划

契约：`docs/superpowers/specs/2026-11-25-p5b-stop-exit-close-archive-design.md`。

## Task 1 — 后端

1. `PipelineTerminationService`（活动实例查找、CAS 终止含 PAUSED、任务/节点 SKIPPED、事务后引擎 cancel
   + 锁释放、`InstanceFinishedEvent(TERMINATED)`）；`MybatisPipelineRepository.terminateInstance`。
2. `ApprovalChain.cancelPending` + `ApprovalService.cancelPending(orderId, snapshotId, reason)`；
   `GateService.invalidateUnfinished(itemId, reason)`（gate_check → INVALID、item.gate_status → CHECK_INVALID）。
3. `ReleaseControlService`（stop/exit/close/archive，四段级联、幂等、权限、范围、原因必填）；
   `AuditAction.STOP` 新增 + 四个动作审计；`NotificationEventKey` 追加 4 个 + 接收人规则。
4. `ReleaseControlController`/`TaskController` 端点 + `ControlDtos`（`{reason}` 入参，响应回显状态）。
5. 归档只读：任务 `ARCHIVED` 时 item 创建/发布/日常/重跑拒绝（40001）；回滚不受影响。
6. 测试 `server/src/test/java/com/sf/release/control/`：设计 §7 全条（fake engine、真实库）。

硬约束：无迁移（状态列都已存在）；不动 ONLINE 正常推进；终止失败不得半途抛给用户；
引擎取消失败只 WARN；审计/通知失败不阻断；全量测试绿（基线 802）。

## Task 2 — 前端

发布页头「停止发布/退出流水线」、变更项「关闭」、任务「归档任务」+ 只读态；三处二次确认与原因；
`scripts/demo-control.sql`、`scripts/verify-p5b-ui.mjs`、截图；`npm run build` 绿。

## Task 3 — 文档

技术方案 §8.11 + §12.2.8；前端设计 §16；未实现清单 GAP-05 完成 + 残留；需求规格 §10 一句；
台账 R61+。

## 门禁

Task 1 → lead 评审（终止 CAS 含 PAUSED、级联完整、幂等、引擎取消 best-effort、归档回滚入口保留）
→ Task 2 → 联调（真实 stop 一个演示单）→ Task 3。