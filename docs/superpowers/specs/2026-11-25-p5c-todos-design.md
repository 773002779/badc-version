# P5-C 待办中心设计（GAP-06）

> 需求 FR-N-03、页面 IA #11「审批待办」；前置 P1–P5-B。纯查询切片，无迁移。

## 1. 范围

一个接口 `GET /api/v1/todos`，按四组返回调用者当前该处理的事，服务端负责数据范围与「是否轮到我」：

| key | 名称 | 口径 |
|---|---|---|
| `test` | 我的待测试 | 变更项 `status=TESTING`，其**最新执行实例** `pipeline_instance.test_ready_at` 非空（测试环境就绪，V9），测试维度非 `TEST_PASS`；调用者是该任务成员 `TEST`（ADMIN 全量） |
| `approval` | 我的审批 | 发布单 `status=PENDING_APPROVAL`，最新快照链上第一个 `PENDING` 步骤的角色 ∈ 调用者的 `user_role`；调用者具备 `control:approve`；**排除自己提交的发布单**（C20，自审批阻断） |
| `execute` | 待我执行 | 发布单 `status=READY`，调用者是任务成员 `OPS` 或具备 `control:execute-prod`（ADMIN 全量） |
| `mine` | 我发起的 | 任务 `creator=调用者`；列出其中仍有未完成变更项（`status` 非终态）的任务与计数 |

- 响应：`{test:{count,items[]}, approval:{...}, execute:{...}, mine:{...}}`，
  `items` 最多 `limit` 条（默认 5，1–20），`count` 为全量计数。
- 每条 item：`objectType, objectId, label, status, statusLabel, waitingSince, waitingHours, unitId, link`。
  `link` 由服务端给前端可直接跳转的相对路径（`/tasks/{taskId}?item={itemId}`、`/releases/{publishNo}`、
  `/tasks/{taskId}`），web 不自行拼规则。
- 数据范围：所有组按 `ScopeGuard` 可见单元过滤（fail-closed）；`mine` 只列调用者创建的任务且其单元在范围内。
- 权限：登录即可；未授权用户只得到空组。

## 2. 查询实现

`TodoQueryMapper`（注解 SQL，一条 SQL 一组，避免 N+1）：

- `test`：`release_item i JOIN release_task t JOIN release_task_member m`，再按 `release_order.item_id`
  关联**最新实例**（`MAX(pi.id)`，`pi.test_ready_at IS NOT NULL`）；条件 `i.status='TESTING'
  AND i.test_status <> 'TEST_PASS' AND m.user_id=#{me} AND m.role='TEST' AND i.unit_id IN (scope)`；
  `waitingSince = pi.test_ready_at`。
- `approval`：最新快照（`MAX(id)` per order）+ `approval` 中 `status='PENDING'` 的最小 `step_no`，
  role ∈ 调用者角色集；`snapshot.submitted_by <> #{me}`；`waitingSince = snapshot.created_at`。
- `execute`：`release_order.status='READY'` 且（`m.user_id=#{me} AND m.role='OPS'` 或 调用者 ADMIN）；
  `waitingSince = order.updated_at`。
- `mine`：`release_task.creator=#{me}` 且存在非终态变更项；计数 = 非终态变更项数；
  `waitingSince = task.updated_at`。
- 计数与明细各查一次（4 组共 8 条 SQL），全部带范围参数；`LIMIT` 在明细 SQL。
- `link` 在 application 层拼（不存库），中文标签由 `ItemStatus`/`OrderStatus` 的现有 label 映射给出
  （web 不维护枚举）。

## 3. 前端

- 侧栏新增「待办」(`/todos`)，徽标 = 四组 count 之和（0 不显示），30s/路由刷新（复用通知铃铛的轮询风格）。
- `/todos` 页：四段列表（`DataSection`），每段标题带计数、行内显示对象、状态、等待时长（「已等待 3 小时」）、
  点击直达 `link`；空段显示轻量空态；底部说明数据范围。
- 无新组件库、无新依赖；亮/暗双主题；`scripts/verify-p5c-ui.mjs` + 截图 `todos-{light,dark}.png`。
- 演示：用现有 demo 单/任务即可（READY 单给 execute、PENDING_APPROVAL 造一条给 approval），
  必要时 `scripts/demo-todos.sql`（幂等、demo 标记）。

## 4. 测试口径

1. test 组：TESTING + ready + 非通过 + 调用者是 TEST 成员 → 命中；未就绪/已通过/非成员 → 不命中。
2. approval 组：PENDING 步骤角色匹配 → 命中；角色不匹配/无 `control:approve`/自己提交 → 不命中。
3. execute 组：READY + OPS 成员 → 命中；非 OPS 且无 execute-prod → 不命中；ADMIN → 命中。
4. mine 组：creator=我 且存在非终态项 → 命中并计数；全部终态 → 不命中。
5. 范围：越界单元的四组均不出现；空库四组全 0、HTTP 200。
6. `limit` 边界（1/5/20，越界 40000）；`link` 形态；`waitingHours` 计算（固定时钟）。
7. ArchUnit/包规则照旧；controller 测试不在 `..web..`。

## 5. 取舍

- 待办是**查询视图**，不落库、不做已读/忽略状态（二期）。
- 审批只取「第一个未签步骤」的角色：双签时第二步的人在当前人签完前看不到待办（避免催错人）。
- `mine` 只统计「我创建的任务」的未完成项，不含「我参与但不是创建人」的任务（后者由审批/测试/执行三组覆盖）。
- 四组之间允许同一对象重复出现（例如我既是提交人又被授权执行），页面按段展示，不做全局去重。