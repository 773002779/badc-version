# P6-B 平台设置补齐设计（GAP-08）

> 需求 FR-5.12 / GAP-08：门禁规则、审批人名单、通知渠道、角色权限、字典、锁超时配置。
> 通知渠道（P4-B）、发布窗口/封板（P3）、CODING 连接（P1）已交付；本切片补 **GATE / APPROVER / LOCK**
> 三段，其余显式说明不做的原因。

## 1. 模型（V30）

```sql
CREATE TABLE app_setting (
  setting_key VARCHAR(32)  PRIMARY KEY COMMENT 'GATE / APPROVER / LOCK',
  value_json  JSON         NOT NULL,
  updated_by  VARCHAR(32)  NULL,
  updated_at  DATETIME(3)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO app_setting (setting_key, value_json, updated_at) VALUES
 ('GATE',     '{"enabledChecks":["SONAR","COMPONENT_VULN","ARCHITECTURE","VERSION_DEFECT","SECURITY_DYNAMIC","AUTO_TEST","DB_NORM"]}', NOW(3)),
 ('APPROVER', '{"opsLead":[],"deptManager":[]}', NOW(3)),
 ('LOCK',     '{"pauseBoundHours":168}', NOW(3));
```

- 通用 key-value + 每段一个**类型化视图**；不建 per-section 表，避免每次加设置项改迁移。
- `GATE.enabledChecks`：`CheckType` 子集（默认 7 项全开）；`APPROVER`：两个审批角色的用户名单
  （默认空 = 沿用 `user_role` 种子）；`LOCK.pauseBoundHours`：暂停超上限（默认 168，即现状）。

## 2. 效果

### 2.1 门禁检查开关

- **新建实例**：`PublishAppService` 读取 `GATE.enabledChecks`，传给
  `PipelineFactory.createOnline(order, item, envDes, enabledChecks)`（旧签名保留 = 全开）。
  `VERIFY` 节点只预生成启用的检查任务；停用的检查不再产生新任务。
- **评估**：`GateService` 在调用 `GateEvaluator.evaluate` 前，用同一份配置过滤 `gate_check` 行：
  停用项的历史行不再阻断（它们是历史，不删除）。`ReleaseView` 的检查面板照旧显示行，停用项标注「已停用」。
- 停用 `SONAR` 等会让「检查通过」更宽松，属高风险配置：`setting:manage` + 审计，页面二次确认。

### 2.2 审批人名单

- `APPROVER` 名单是 **角色成员名单**：保存时在一个事务里把 `user_role` 中
  `OPS_LEAD`/`DEPT_MANAGER` 行替换为名单（先校验用户存在且 ACTIVE，名单可为空 = 回退到「该角色无人，
  兜底系统负责人」的既有行为），随后写 `SETTING_UPDATE` 审计。
- 效果即时（`JdbcAuthProvider` 每次请求读 `user_role`）；不新增审批表、不改审批链结构。
- **不提供**「按系统/单元指定审批人」：一期角色是平台级（Q2 未定）。

### 2.3 锁超时

- `pauseBoundHours` 由 `UnitLeaseRenewJob` **每轮**读取（覆盖 `release.unit-lease.max-paused-hours`），
  读失败回退 env 默认；修改即时生效。
- **租约 TTL（4h）仍为代码常量**：Redis/DB 租期在获取时写入，动态改 TTL 需要重建锁契约，本阶段不做。

## 3. 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/settings/gate` | `setting:manage` | `{enabledChecks:[CheckType], updatedBy, updatedAt}` |
| PUT | `/api/v1/settings/gate` | `setting:manage` | 同上；未知检查项 40000；至少 1 项（全停用需走「跳过检查」的显式豁免路径，不允许 0） |
| GET | `/api/v1/settings/approvers` | `setting:manage` | `{opsLead:[userId], deptManager:[userId], updatedBy, updatedAt}` |
| PUT | `/api/v1/settings/approvers` | `setting:manage` | 用户必须存在且 ACTIVE；去重；空数组合法 |
| GET | `/api/v1/settings/lock` | `setting:manage` | `{pauseBoundHours, updatedBy, updatedAt}` |
| PUT | `/api/v1/settings/lock` | `setting:manage` | 1–8760 小时 |

统一响应 `ApiResponse`；每次 PUT 写 `SETTING_UPDATE` 审计（objectId = `GATE|APPROVER|LOCK`，
`before/after` 为整段 JSON，`scope_*` 空 = 平台级，仅 ALL 范围可读）。

## 4. 页面

- 设置页新增三块（在「消息通知」之后）：
  1. **门禁规则**：7 个检查项复选框（当前启用项回显）+ 保存（二次确认提示「停用后新流水线不再执行该项」）；
  2. **审批人名单**：两个角色各一个多选用户输入（可留空 = 兜底系统负责人）+ 保存；
  3. **锁超时**：暂停上限小时数 + 保存（提示「租约本身仍为 4 小时」）。
- 无 `setting:manage` 时只读并给原因（沿用既有设置页模式）。
- 亮/暗双主题；`scripts/verify-p6b-ui.mjs` + 截图 `settings-rules-{light,dark}.png`。

## 5. 测试口径

1. 默认值：三段 GET 返回种子值；未知 key 40000。
2. GATE：停用某检查后新建实例的 VERIFY 任务数减少；停用项的历史 FAIL 行不再阻断评估；
   0 项 40000；未知项 40000；UNIT 与 LIB 路径均生效（LIB 三项检查里停用的不再触发）。
3. APPROVER：保存后 `user_role` 只替换两个角色行、其他角色不动；不存在/非 ACTIVE 用户 40000；
   空名单清空该角色；审批决策在名单变更后即时生效（用真实用户判定）。
4. LOCK：保存后 renew job 下一轮使用新值；越界 40000；读失败回退 env（模拟非法 JSON）。
5. 审计：每次 PUT 一行 `SETTING_UPDATE`（before/after、reason 可选）。
6. 权限：无 `setting:manage` 40009；页面只读。
7. ArchUnit/包规则照旧。

## 6. 取舍

- **字典（DICT）不做**：当前没有任何消费者，落表即「存而不用」；需要时按同一 `app_setting` 模式追加。
- **角色权限映射（role_permission）不做页面**：改它等于改平台权限模型，风险高且无明确需求；
  沿用 DB 种子 + 审计。
- **锁租约 TTL 不可配**（见 §2.3）；`pauseBoundHours` 可配。
- **审批人变更即时生效**，没有缓存，因此每次请求多一条 `user_role` 查询（已有）。
- **停用检查不删除历史行**，且**立即生效**：新建实例不再预生成该检查任务，已在途实例的旧任务仍可跑完，
  但其结果在评估时被忽略；页面提示「停用对在途发布立即生效」。