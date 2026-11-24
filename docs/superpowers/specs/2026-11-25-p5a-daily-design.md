# P5-A 日常发布设计（GAP-04）

> 需求 FR-D-01..04、C10/C24、J4。前置：P1–P4（含管线引擎、审计、通知）。本阶段只做「日常发布」，
> 停止/退出/关闭/归档（GAP-05）与待办（GAP-06）另两个切片。

## 1. 语义与边界

- 日常发布 = 一条**简化流水线**：`BUILD（复用单元唯一构建任务）→ DAILY_DEPLOY（日常部署）`，
  无 VERIFY、无 PROD_DEPLOY、无审批、无窗口/封板判定、无生产准入。
- **不建发布单**（`pipeline_instance.order_id = 0`）、**不占部署单元锁**、**不改变更项状态**、
  不进回滚候选。
- 可对同一变更项**多次并行**发起（FR-D-01）；CODING 构建并发由引擎侧限流。
- 日常发布成功 ≠ 可上线（J4）：日常不复用测试/生产镜像，上线仍走完整构建与测试部署。
- 日常部署目标：`env_de.env_kind = 'DAILY'` 的 DE；单元没有日常 DE 时回退到 `TEST` DE
  （C24「复用测试部署任务，或独立日常部署任务」）。
- CODING 任务绑定：优先 `coding_task_binding.task_kind = 'DAILY_DEPLOY'`，缺失回退 `TEST_DEPLOY`；
  构建固定用 `BUILD` 绑定（同上线，回退 item.build_plan_id）。

**非目标**：日常的检查/门禁、日常镜像入库、日常通知（FR-N-01 未列；只写审计 DAILY_START）、
日常并行上限、日常环境配置页（已有 env_de 配置即用）。

## 2. 模型改动（V28 + 枚举）

```sql
ALTER TABLE pipeline_instance
  ADD COLUMN item_id      BIGINT      NULL COMMENT 'daily: the change item, NULL for online',
  ADD COLUMN unit_id      BIGINT      NULL COMMENT 'daily: the unit, NULL for online (order carries it)',
  ADD COLUMN requested_by VARCHAR(32) NULL COMMENT 'daily: who started it (online uses order.executed_by)',
  ADD KEY idx_instance_item (item_id);
```

- 只对 `DAILY` 实例填 `item_id/unit_id/requested_by`；ONLINE 保持 NULL（上线仍经 order 解析，
  不动既有路径）。
- 枚举追加（**只追加，不改既有值顺序**）：`NodeType.DAILY_DEPLOY`、`TaskType.DAILY_DEPLOY`。
  `ExecType.DAILY` 已存在（V8 注释也早已预留）。
- 所有对 `NodeType`/`TaskType` 的穷举 `switch` 必须补新常量（编译驱动）：至少
  `PipelineEventListener.itemEventOf`（DAILY_DEPLOY → `null`，日常不驱动状态机）、
  任务完成日志、查询/标签映射（`ReleaseQueryService`/`NodeType` 展示名）。

## 3. 工厂与实例

`PipelineFactory.createDaily(ReleaseItem item, long unitId, List<EnvDe> envDes, String requestedBy, Instant now)`：

- 节点：
  1. `BUILD`（seq 0）：`CODE_CHECK → PKG_BUILD → IMG_BUILD`，与上线同一条链；
  2. `DAILY_DEPLOY`（seq 1）：每个目标 DE 一条 `DEPLOY` 任务，`batch_no = deploy_batch_no`（仅展示），
     `target_id = de.id`。
- 实例：`orderId=0`、`execType=DAILY`、`triggerType=INITIAL`、`currentNode=BUILD`、`status=PENDING`、
  `pipelineNo=PL-...`、`itemId/unitId/requestedBy` 按设计填。
- 目标 DE 选择顺序：`DAILY` 非空 → 用 DAILY；否则用 `TEST`；两者都空 → 创建前报 40001
  （「单元没有日常/测试环境 DE，无法日常发布」）。
- 构建任务复用：与上线同一个 `BUILD` 绑定；`BuildContext` 由新方法
  `dailyBuildContextOf(instanceId)` 解析（item 分支/planId/unitId，commit 取 instance.commit_sha）。

## 4. 部署处理

`DailyDeployNodeHandler`（domain handler，注册进 `NodeHandlerRegistry`）：

- `start`：把 `DAILY_DEPLOY` 节点里所有 `PENDING` 的 DEPLOY 任务**一次全部触发**（不串行分批；
  日常没有 C14 分批要求），每个任务调用 `CicdEngine.triggerDeploy(DeployCmd)`，
  置 `WAITING_EXTERNAL` + 60 分钟 deadline；已 `WAITING_EXTERNAL/RUNNING` 的任务不重复触发。
- `onTaskCompleted`：一个任务成功不触发别的（其余已在跑）；节点完成由 orchestrator 的
  「所有计数任务终态」规则判定。
- 失败：走 orchestrator 既有失败路径（节点/实例 FAILED），不重试、不静默。
- 部署上下文：`dailyDeployContextOf(instanceId)` 解析 unitId、目标 DE id 已是 task.targetId、
  绑定 taskId（DAILY_DEPLOY 优先，TEST_DEPLOY 回退）、分支；不校验镜像一致性（日常不要求同镜像）。

## 5. 服务与接口

`DailyReleaseService`（application）：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/v1/items/{itemId}/daily` | `release:daily` + 单元范围 | 创建并自动启动；返回 `{pipelineNo, instanceId}` |
| GET | `/api/v1/items/{itemId}/daily?page=&size=` | 登录 + 范围 | 该变更项的日常记录，`created_at DESC` |
| GET | `/api/v1/daily/{pipelineNo}` | 登录 + 范围 | 日常实例详情（节点/任务/外部链接/摘要） |

- `start` 校验顺序：权限 → 变更项存在且 `type=UNIT`（LIB 拒绝，40001）→ 单元在范围内（40009）→
  任务未归档（若任务 `ARCHIVED` → 40001「任务已归档」）→ 目标 DE/绑定就绪（40001）→ 创建实例
  （一个事务，无锁行）→ 审计 `DAILY_START`（对象类型 `ITEM`、`objectId=itemId`、
  `scope_unit_id`）→ `orchestrator.advance(instanceId)`。
- 允许同一 item 多个日常实例并行；不做「已有运行中日常」限制。
- 记录/详情行：`pipelineNo, status, requestedBy, branch(commit), createdAt, startAt, endAt,
  imageDigest, nodes[{nodeType,status,tasks[{taskType,status,externalUrl,summary}]}]`。
- 不写 `release_order`、不写 `release_item.status`、不占锁。

## 6. 前端

- 变更项页/任务详情变更项行：「日常」按钮（`release:daily`；无权限禁用给原因；LIB 项不显示）。
- 日常弹窗：确认目标环境（DAILY/TEST 命中情况由接口返回或按钮旁提示）+ 「发起后不占锁、不改变更项状态」。
- 日常记录：变更项行展开或任务详情「日常发布」小块，列 时间/分支/commit/执行人/结果/日志链接
  （日志链接 = 任务 `external_url`），分页；点行开 `/daily/{pipelineNo}` 详情抽屉或页面。
- 亮/暗双主题；`scripts/verify-p5a-ui.mjs` + 截图（`daily-{light,dark}.png`）。
- 演示数据：`scripts/demo-daily.sql` 造日常 DE（kind=DAILY）或直接复用 TEST DE + 2 条历史记录
  （幂等、demo 标记）。

## 7. 测试口径

1. 工厂：两节点、构建链 3 任务、每日 DE 一条 DEPLOY、`orderId=0`、`execType=DAILY`、
   `itemId/unitId/requestedBy` 正确；DAILY 缺失回退 TEST；两者皆无 → 40001。
2. 不占锁：发起前后 `deploy_unit_lock` 无该单元行；同单元在线发布进行中仍可日常发起。
3. 不改变更项：发起/完成前后 `release_item.status` 不变；`PipelineEventListener` 不为日常实例产生
   item 事件（orderId=0 提前返回，已有规则的回归）。
4. 并行：同一 item 连续两次日常都成功创建，两条实例互不影响。
5. Handler：fake engine 收到每 DE 一次 `triggerDeploy`；任务全部成功 → 节点/实例 DONE；
   引擎失败 → 任务/节点/实例 FAILED 且原因落库。
6. 接口：权限（无 `release:daily` → 40009/403）、越界单元、LIB 项、归档任务、分页与详情字段。
7. 审计：`DAILY_START` 一行（actor/object/scope），失败不阻断。
8. ArchUnit：web 不 import domain/infra 枚举；controller 测试不在 `..web..`。

## 8. 取舍

- 日常**并行不分批**（与生产分批不同），失败也不回滚任何已部署 DE；记录里能看到哪些 DE 成功。
- 日常**不做镜像一致性校验**，因为本就不与测试/生产共用镜像（J4）。
- 日常**不写通知**（FR-N-01 未列），只写审计；若后续要通知，加 event key 即可。
- `pipeline_no` 仍全局唯一；日常记录以 `item_id` 索引查询，不参与全局发布记录页（该页只列发布单）。