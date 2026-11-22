# P3 · 生产发布管控与执行 — 任务级计划（2026-11-22）

设计见 `docs/superpowers/specs/2026-11-22-p3-production-control-design.md`。本计划只列实现边界，
行为细节以设计为准。三个后端任务串行，前端与文档在它们之后。

## 约定（每个任务都适用）

- 包结构：`domain/{window,freeze,approval,admission}`（纯领域，无 Spring）、`application`（服务 +
  `*View` 读模型）、`infra/repo`（Mapper + PO）、`web/controller` + `web/dto`。
- ArchUnit：`web` 不得依赖 `domain`/`infra`；`domain` 不得依赖 `application`/`infra`/`web`。
  Controller 只接 application 的 record。
- 权限点：`Permissions.*` + `@RequirePerm`；数据范围用 `ScopeGuard.assertUnitInScope(unitId)`。
- 错误：`BizException(BizError.X, "中文原因", Map.of("field", ...))`；复用 40000/40001/40002/40003/40007/40009。
- 时间：`Instant`（UTC）落库；`LocalTime`/`LocalDate` 只用于窗口规则；窗口时区默认 `Asia/Shanghai`。
- 迁移编号 `V21__production_control.sql`，DDL 注释用英文（Flyway 在本机按默认字符集解析）。
- 测试：`@SpringBootTest @ActiveProfiles("test")`（schema 会被 Flyway clean + migrate）；
  聚合/算法用纯单元测试。测试类不要放在 `..web..` 包里（ArchUnit 会扫）。
- 用户可见文案中文；代码注释英文；不新增依赖。
- 每个任务结束：`mvn -o test -Dtest=...` 绿；不要 `mvn verify`（8080 有服务占着 jar）。
- 不要改 `web/`（前端单独做）。

---

### Task 1: 控制基础、发布窗口与审批链

**迁移 V21**

```sql
release_window(id, scope, subsystem_id, enabled, timezone, created_at, updated_at,
               UNIQUE KEY uk_window_scope(scope, subsystem_id))
window_rule(id, window_id, day_of_week 1..7, start_time TIME, end_time TIME)
window_exception(id, window_id, the_date DATE, mode OPEN|CLOSED, start_time TIME NULL, end_time TIME NULL,
                 UNIQUE KEY uk_window_date(window_id, the_date))
freeze_event(id, scope, subsystem_id NULL, reason, impact, initiator_id, start_at, end_at,
             status ACTIVE|RELEASED|EXPIRED, released_by, released_at, created_at,
             KEY idx_freeze_range(status, start_at, end_at))
approval(id, order_id, step_no, role, status PENDING|APPROVED|REJECTED|CANCELED,
         decided_by, decided_at, comment, created_at, UNIQUE KEY uk_approval_step(order_id, step_no),
         KEY idx_approval_order(order_id))
admission_snapshot(id, order_id, item_id, planned_release_time, in_window TINYINT, window_id NULL,
                   frozen TINYINT, freeze_ids TEXT NULL, max_severity, check_conclusion,
                   waiver_ids TEXT NULL, test_conclusion, test_pass_by, oa_ticket_no, image_digest,
                   chain_json TEXT NULL, created_at, KEY idx_snapshot_order(order_id))
```

**领域（纯 Java）**

- `domain/window/WindowRule(int dayOfWeek, LocalTime start, LocalTime end)`
- `domain/window/WindowException(LocalDate date, Mode mode, LocalTime start, LocalTime end)`
- `domain/window/ReleaseWindow(scope, subsystemId, enabled, zoneId, List<WindowRule>, List<WindowException>)`
  - `boolean covers(LocalDateTime at)`：例外 → 规则；跨零点见设计 4.1。
  - `Optional<WindowSpan> nextWindow(LocalDateTime from, int searchDays)`：分钟步进，最长 7 天。
- `domain/window/WindowCheck(boolean inWindow, WindowSpan span, WindowSpan next, boolean configured)`
  - `configured=false` 时 `inWindow=false`（未配置不等于免审）。
- `domain/approval/ApprovalRole(OPS_LEAD, DEPT_MANAGER)`、`ApprovalStatus`、`ApprovalStep(stepNo, role, status, decidedBy, decidedAt, comment)`
- `domain/approval/ApprovalChain(List<ApprovalStep>)`：`nextPending()`、`complete()`、`reject()`、
  `appendIfMissing(role)`。

**应用 / 仓储**

- `WindowService`：`effectiveWindow(long subsystemId)`（系统覆盖 → 全局 → 未配置）、
  `save(scope, subsystemId, enabled, zoneId, rules, exceptions)`（校验：每天规则不重叠、
  时长 30 分钟–12 小时否则 WARN 但保存、start==end 拒绝）、`check(subsystemId, Instant at)`。
- `WindowAppService` 读写模型：`WindowView(scope, subsystemId, enabled, timezone, rules[], exceptions[],
  inherited)`, `WindowCheckView(inWindow, span, next, configured, windowId)`。
- `ApprovalService`：`ensureChain(long orderId, List<ApprovalRole>)`（幂等，只追加缺的、
  保留已决）、`appendIfMissing(orderId, role)`、`dropPending(orderId, role)`、
  `List<ApprovalStep> steps(orderId)`、`void decide(orderId, stepNo, approved, actor, comment)`
  （只允许最低未决步骤；self-approval 由调用方校验）。
- Mapper：`ReleaseWindowMapper`、`WindowRuleMapper`、`WindowExceptionMapper`、`FreezeMapper`、
  `ApprovalMapper`、`AdmissionSnapshotMapper` + 对应 PO。
- 接口：
  - `GET /api/v1/windows?subsystemId=` → `WindowView`（含 `inherited`、`next` 由 check 给）；登录即可。
  - `PUT /api/v1/windows` body `{scope, subsystemId, enabled, timezone, rules:[{dayOfWeek,start,end}],
    exceptions:[{date,mode,start,end}]}`；`setting:manage`。
  - `POST /api/v1/windows/check` body `{subsystemId, at}` → `WindowCheckView`；登录即可。
- 测试（`window/` 包）：跨零点前置日规则、例外 CLOSED/OPEN、未配置→OUT、nextWindow 跳过整段、
  保存校验（重叠、start==end、过短 WARN 仍保存）；`approval/`：链顺序、重复 ensure 幂等、
  追加/移除、只允许最低步骤、驳回后其余 CANCELED。

**完成定义**：V21 应用成功；窗口判定与审批链单测绿；`GET/PUT windows`、`check` 有控制器测试
（无权限 40009、参数 40000）。

---

### Task 2: 封板、准入判定与提交生产

**封板**

- `FreezeService`：`create(scope, subsystemId, reason, impact, startAt, endAt, initiator)`
  （`control:freeze`；endAt>startAt；立即 ACTIVE）、`release(id, actor, reason)`
  （`control:unfreeze`；只能解除 ACTIVE）、`List<FreezeView> active(subsystemId, at)`、
  `expireDue(now)`（每日扫描用）。
- 生效时在途处理（同一事务）：查 `release_order` join `release_item` 得受影响单
  （`item.status IN (SUBMITTED,PENDING_APPROVAL,READY)` 且 unit.subsystem 命中）；
  - 每条：`approvalService.appendIfMissing(orderId, DEPT_MANAGER)`；
  - `READY` 的变更项：`ItemTransitions.apply(itemId, FREEZE_HIT)`（→ PENDING_APPROVAL）；
  - `SUBMITTED` 的：`ItemTransitions.apply(itemId, NEED_APPROVAL)`。
- 解除：受影响的 `PENDING_APPROVAL` 单重算链——按最新快照 `inWindow`：
  `IN` → 免审（`dropPending(DEPT_MANAGER)`+`dropPending(OPS_LEAD)`），`OUT` → 只留 `OPS_LEAD`
  （`dropPending(DEPT_MANAGER)`）；已 APPROVED 的步骤不删。链若已全通过 → 变更项
  `APPROVE_ALL`（PENDING_APPROVAL→READY）。
- 接口：`GET /api/v1/freezes?active=&subsystemId=`（登录）、`POST /api/v1/freezes`（`control:freeze`）、
  `POST /api/v1/freezes/{id}/release`（`control:unfreeze`，body `{reason}`）。

**准入与提交**

- `AdmissionService.evaluate(orderId|itemId, plannedAt, oaTicket)` → `AdmissionDecision`
  （步骤 1–6 见设计 4.5；每步 `passed` + `reason` + `nextAction`）。复用
  `GateService.decisionFor`/`view` 与 `release_item` 的 `test_status`。
- `SubmitProdService.submit(publishNo, plannedAt, oaTicket)`：
  - `ScopeGuard` + `control:submit-prod`；变更项必须 `GATE_PENDING`；
  - 判定失败 → 40002（检查/测试/豁免）/40000（OA/时间）；封板不阻断提交，只加双签；
  - 写 `release_order`（oa、planned、window_result、deadline=planned+2h、status=SUBMITTED）；
    写快照；`ItemTransitions` 依次 `SUBMIT` 再 `NO_APPROVAL` 或 `NEED_APPROVAL`；
  - 审批链 `approvalService.ensureChain(orderId, chain)`；
  - 返回 `AdmissionView`（快照 + 链 + 下一步）。
- 接口：`POST /api/v1/releases/{publishNo}/submit-prod` body `{plannedReleaseTime, oaTicketNo}`；
  `GET /api/v1/releases/{publishNo}/admission`（快照 + 审批链 + 复核预览）；`POST
  /api/v1/releases/{publishNo}/resubmit`（REJECTED→SUBMITTED，重新判定、生成新快照）。
- 测试：免审（窗口内）→ READY；窗口外 → OPS_LEAD 单签；封板期 → 双签；检查阻断 40002 带缺项；
  OA 格式 40000；重复提交 40001；重提生成新快照；封板生效把 READY 拉回 PENDING_APPROVAL。

**完成定义**：封板/准入/提交三个服务的场景测试全绿；控制器测试覆盖权限与 400 码。

---

### Task 3: 审批、执行复核、生产部署处理器

**审批**

- `POST /releases/{publishNo}/approve`（`control:approve`，body `{comment?}`）：
  当前步骤 = 最低未决；`actor==submitter` → 40008；通过 → 若还有未决步骤则停在下一步，
  全部通过 → 变更项 `APPROVE_ALL`（→READY）+ 订单 `READY`。
- `POST /releases/{publishNo}/reject`（`control:approve`，body `{reason}` 必填）：
  变更项 `REJECT`（→REJECTED）、其余步骤 CANCELED、订单 REJECTED。
- `GET /releases/{publishNo}/admission` 已在 T2；审批后返回最新链。

**执行与复核**

- `ReviewService.review(orderId)` → `{frozen, freezeIds, waiverInvalid, digestMatched, blocked, reasons[]}`；
  封板新出现 → 阻断并触发 `FREEZE_HIT`（READY→PENDING_APPROVAL + 补步骤）；
  豁免失效 → 阻断 40002；digest 不一致 → 40007。
- `ExecuteProdService.execute(publishNo, actor)`：
  - `control:execute-prod`；变更项 `READY`；有最新快照；复核通过；
  - `EXECUTE`（READY→PROD_DEPLOYING）、订单 `EXECUTING`；
  - 实例必须是 `PAUSED` 在 `PROD_DEPLOY`：`resumeInstance` + `advancer.advance`（复用 P2 的 resume 逻辑，
    但权限/前置换成生产执行）；
  - 返回 `{publishNo, status, node, batchCount}`。
- 恢复（批次边界暂停后）：`POST /releases/{publishNo}/execute` 在 `PROD_DEPLOYING` 且实例 `PAUSED`
  时允许再次调用（只看复核 + 恢复），不得重复 `EXECUTE` 事件。

**生产部署处理器**

- `domain/pipeline/handler/ProdDeployNodeHandler implements NodeHandler`（`NodeType.PROD_DEPLOY`）：
  - `start(instance)`：读节点任务（`DEPLOY`，按 `batchNo` 升序）；找最低未完成批次；
    **批次边界检查**：`FreezeService.active(unitSubsystem)` 非空或门禁 `finalBlock` → 不触发，
    调 `repository.pauseInstance(instance, PROD_DEPLOY, reason)`；
    否则触发该批所有 `PENDING` 任务（并行、`DeployCmd` 用批内 DE 的 envKind/deName、8 分钟 deadline）。
  - `onTaskCompleted(instance, task)`：该批全部 SUCCESS → 触发下一批（再走边界检查）；
    任一批内任务 FAILED → 不触发后续（由编排器判节点失败）。
  - `failureReason`：无。
- 装配：`PipelineConfig` 增加 bean；`WiringTest` 断言 PROD_DEPLOY 有处理器。

**测试**：审批三条链；自审 40008；驳回/重提；复核三态；execute 前置（未 READY 40001、复核阻断）；
处理器：批内并行触发、整批成功推进下一批、失败停批、边界封板暂停、续跑不重复触发。

**完成定义**：`mvn -o test` 全绿（含既有 371）；处理器 bean 出现在 `WiringTest`。

---

### Task 4: 前端（由 lead 完成）

- 发布页：提交生产对话框（计划时间 + OA 号 + 窗口预览 + 封板提示）、审批区（链 + 通过/驳回）、
  执行按钮（复核结果 tooltip）、批次进度（复用节点任务行）、封板横幅。
- 系统详情：发布窗口配置（星期规则 + 例外）。
- 设置页或独立页：封板管理（列表 + 新建 + 解除）。
- 审批入口按 `control:approve` 与「是否当前步骤」禁用并给原因。

### Task 5: 文档与台账

- 需求 5.6 标注落地与偏差；技术方案 8 章/12.2.3 补实际接口；台账 R34–R4x；
  截图 6 张（提交、审批、封板、窗口、执行中、完成）。