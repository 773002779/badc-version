# P4-C 审计日志设计

> 阶段：P4-C（GAP-03）。需求 FR-N-02；技术方案 §4.1/§14 已把 `audit_log` 列为审计表。
> 目标：谁在何时对哪个对象做了什么，可检索、可导出，且失败不影响业务。

## 1. 范围

**做**

1. `audit_log` 表：actor、action、object_type/object_id、before/after JSON、reason、summary、
   trace_id、数据范围列、时间索引；只插入，不更新/删除（保留期策略属 P7）。
2. 写入覆盖（现有可落地的动作）：
   - 发布：`PUBLISH`（发起发布）、`SUBMIT_PROD`（提交生产）、`EXECUTE_PROD`（生产执行）、`RERUN`（重跑）；
   - 审批：`APPROVE`、`REJECT`（发布审批链，含双签）；
   - 门禁：`GATE_TEST_PASS`、`GATE_TEST_FAIL`、`GATE_TEST_REVOKE`、`GATE_REFRESH`、
     `WAIVER_REQUEST`、`WAIVER_APPROVE`、`WAIVER_REJECT`；
   - 管控：`FREEZE_CREATE`、`FREEZE_RELEASE`、`FREEZE_EXPIRE`、`ROLLBACK_EXECUTE`；
   - 配置：`SETTING_UPDATE`（CODING 连接、通知设置）、`WINDOW_UPDATE`（窗口规则）、
     `RESOURCE_UPDATE`（子系统/单元/任务映射的增改）。
3. 查询：`GET /api/v1/audit`（对象、动作、人、时间范围、分页，fail-closed 数据范围）；
   导出：`GET /api/v1/audit/export`（同一筛选，CSV，上限 5000 行，UTF-8 BOM）。
4. 页面：单元详情「操作日志」Tab + 全局 `/audit` 审计页（筛选 + 导出）。
5. 测试：动作留痕率（上表每个动作至少一条）、before/after、trace_id、查询过滤与范围、导出内容与上限、
   写失败不影响业务。

**不做**

- 关闭/退出/归档、日常、强拆锁、紧急放行的留痕：随 GAP-05/GAP-04/GAP-11 一起接（枚举先占位，不写）。
- 审计保留期与归档清理、审计告警、字段级 diff 高亮：P7。
- 不可篡改（WORM/哈希链）：一期不做，只保证应用层不更新/删除。

## 2. 数据模型（V27）

```sql
CREATE TABLE audit_log (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  actor             VARCHAR(32)  NOT NULL COMMENT 'X-User-Id of the caller (SYSTEM for jobs)',
  action            VARCHAR(48)  NOT NULL COMMENT 'PUBLISH / SUBMIT_PROD / ...',
  object_type       VARCHAR(32)  NOT NULL COMMENT 'ORDER / ITEM / UNIT / SUBSYSTEM / FREEZE / WAIVER / SETTING',
  object_id         VARCHAR(64)  NOT NULL COMMENT 'publish number / id / setting scope',
  object_label      VARCHAR(200) NULL,
  summary           VARCHAR(512) NOT NULL COMMENT 'one-line human summary',
  reason            VARCHAR(512) NULL,
  before_json       JSON         NULL,
  after_json        JSON         NULL,
  scope_unit_id     BIGINT       NULL COMMENT 'unit the object belongs to, for scope filtering',
  scope_subsystem_id BIGINT      NULL,
  trace_id          VARCHAR(64)  NULL,
  created_at        DATETIME(3)  NOT NULL,
  KEY idx_audit_object (object_type, object_id, created_at),
  KEY idx_audit_actor (actor, created_at),
  KEY idx_audit_unit (scope_unit_id, created_at),
  KEY idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- `before_json/after_json` 只存**变化的字段**（不是整行），由触发点给 `Map<String,Object>`；
  无变化/新增时 `before=null`，删除时 `after=null`。
- `trace_id` 取 MDC 的 `traceId`（`TraceIdFilter` 已写入），无则空。
- 数据范围：`scope_unit_id` 为单元级动作；系统级动作写 `scope_subsystem_id`；平台级（设置）两者都空，
  只有 `ALL` 范围可读。查询/导出按 `ScopeGuard` 的可见单元集合过滤（fail-closed）。
- `action` 中文名在 `AuditAction` 枚举里，接口返回 `actionLabel`，web 不维护枚举。

## 3. 写入模型

`AuditService.record(AuditCommand)`：

- `REQUIRES_NEW`（`TransactionTemplate`，与通知相同的理由：提交失败也能吞掉）；
- **吞异常并记 error 日志**，任何审计失败不得改变业务结果；重复调用不去重（审计是流水，不是通知）；
- 触发点只构造 command（action、对象、summary、reason、before/after、scope），不拼 SQL；
- 在业务状态**写入之后**调用（记录的是已发生的事实）；如果业务随后回滚，审计行会留下——与通知同款
  窗口，记为取舍（§7）。

`AuditCommand` 字段：`action, objectType, objectId, objectLabel, summary, reason, before, after,
scopeUnitId, scopeSubsystemId`；`actor` 一律取 `UserContext.current().userId()`，job 里显式传
`SYSTEM`（`FREEZE_EXPIRE`）。

## 4. 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/audit?objectType=&objectId=&action=&actor=&from=&to=&page=&size=` | 登录即可 | 按范围过滤，`created_at DESC, id DESC`；返回 `{total,pageNo,pageSize,records}` |
| GET | `/api/v1/audit/export?<同筛选>` | 登录即可 | `text/csv; charset=UTF-8`，带 BOM，表头中文；最多 5000 行，超出在末行提示 |

行：`id, action, actionLabel, actor, objectType, objectId, objectLabel, summary, reason,
before (string JSON), after (string JSON), traceId, createdAt`。

参数校验：`from > to` → 40000；`size` 1–100；`action` 不在枚举 → 40000；`objectType` 白名单。
CSV 字段转义：双引号包裹 + 内部 `"` 翻倍，换行保留；`before/after` 压成单行。

## 5. 前端

- 单元详情新增「操作日志」Tab：`DataSection` + 表格（时间、操作人、动作、摘要、原因、traceId 折叠），
  按 `objectType=UNIT&objectId={id}` 或 `unitId` 过滤（接口加 `unitId` 便捷筛选）；分页。
- `/audit` 全局页：筛选（动作下拉用服务端 label、对象类型、对象 ID、操作人、时间范围）+ 表格 +
  「导出 CSV」（前端用 `window.open` 带筛选参数，或 fetch blob 下载）+ 空态；导航「审计日志」。
- 亮/暗双主题；验证脚本断言两个页面 + 0 console error；截图 `audit-{light,dark}.png`、
  `unit-audit-{light,dark}.png`。

## 6. 测试口径

1. 每个动作一条集成断言：调用真实服务 → `audit_log` 出现对应行，actor/object/action/summary 正确；
   至少覆盖 PUBLISH、SUBMIT_PROD、EXECUTE_PROD、RERUN、APPROVE、REJECT、GATE_TEST_PASS、
   GATE_TEST_REVOKE、GATE_REFRESH、WAIVER_REQUEST/APPROVE、FREEZE_CREATE/RELEASE、ROLLBACK_EXECUTE、
   SETTING_UPDATE。
2. before/after：状态变化动作（如 FREEZE_RELEASE、ROLLBACK、SETTING_UPDATE）记录变更前后字段；
   新增对象 `before=null`。
3. trace_id：请求内动作带当前 traceId；无 MDC 时为空不报错。
4. 查询：objectType/objectId/action/actor/时间范围过滤；分页 total 正确；越界 `unitId` 不可见（空结果）。
5. 导出：表头 + BOM + 转义（含逗号/引号/换行的 summary）；超过 5000 行截断并提示。
6. 失败隔离：audit 写入抛异常（模拟 mapper 异常）时业务方法仍成功。
7. ArchUnit：web 不 import domain/infra 枚举；controller 测试不在 `..web..`。

## 7. 取舍

- **非原子**：审计与业务事务分离（REQUIRES_NEW），业务回滚可能留下一条「未发生」的审计；P7 若要求
  严格一致需 outbox。
- **before/after 是字段子集**，不是整行快照；查「当时完整状态」需结合业务历史表。
- **平台级设置**只有全量范围可见；系统级动作按 `scope_subsystem_id` 过滤，单元级按 `scope_unit_id`。
- **保留期**未实现；表按年增约 10 万行的假设（技术方案 §14）不需要分库。
- **动作枚举会随 P5 增长**；新增动作只加枚举与触发点，不改表结构。