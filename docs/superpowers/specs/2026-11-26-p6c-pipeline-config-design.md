# P6-C 流水线配置页设计（GAP-09）

> 需求 IA #8 / GAP-09：**单元级**流水线配置（安全子集），不改变四节点主干。
> 与 P6-B 的关系：P6-B 是**平台级**检查开关；本切片是**单元级**覆盖 + 超时 + 批次顺序。

## 1. 模型（V31）

```sql
CREATE TABLE unit_pipeline_config (
  unit_id                 BIGINT PRIMARY KEY,
  env_checks_enabled      TINYINT      NOT NULL DEFAULT 1 COMMENT 'run SECURITY_DYNAMIC/AUTO_TEST/DB_NORM',
  build_timeout_minutes   INT          NOT NULL DEFAULT 60,
  deploy_timeout_minutes  INT          NOT NULL DEFAULT 8,
  batch_order             VARCHAR(8)   NOT NULL DEFAULT 'FORWARD' COMMENT 'FORWARD / REVERSE',
  updated_by              VARCHAR(32)  NULL,
  updated_at              DATETIME(3)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- **无行 = 默认值**（不在单元创建时插入）；保存时 upsert。
- 约束：build 5–240 分钟、deploy 1–120 分钟、`batch_order` 两值之一；`env_checks_enabled` 布尔。
- `batch_order=REVERSE` 只影响**新建实例**：生产族 DE 的 `batch_no` 变成
  `maxBatch + 1 - original`（与回滚同款），执行顺序即「原最高批 → 原最低批」；测试部署不分批，不受影响。

## 2. 效果

- **环境级检查开关**（`env_checks_enabled=false`）：新建实例的 `VERIFY` 不再预生成
  `SECURITY_DYNAMIC/AUTO_TEST/DB_NORM` 三条任务；与平台级 `GATE.enabledChecks` 取**交集**。
  评估侧同样忽略被停用项（P6-B 的过滤 + 单元配置一并传入）。
- **构建超时**：`BUILD` 节点 `CODE_CHECK/PKG_BUILD/IMG_BUILD` 与 `LIB_PUBLISH` 的
  `deadline_at = start + build_timeout_minutes`；`DAILY` 构建同用。
- **部署超时**：`TEST_DEPLOY`/`PROD_DEPLOY`/`DAILY_DEPLOY` 的 `deadline_at = start + deploy_timeout_minutes`
  （现有 8 分钟默认值不变）。
- 处理器通过 `PipelineRepository.pipelineConfigOf(instanceId)` 读取（订单 → 变更项 → 单元），
  读失败/无行回退常量默认；**在途实例读当轮配置**（超时按任务进入时生效，不做历史重算）。

## 3. 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/units/{unitId}/pipeline-config` | `setting:manage` + 范围 | 无行返回默认值 + `configured:false` |
| PUT | `/api/v1/units/{unitId}/pipeline-config` | `setting:manage` + 范围 | 全量替换；越界 40000；审计 `SETTING_UPDATE`（object `UNIT`，objectId=unitId，before/after 整段，scope_unit_id=unitId） |

响应：`{unitId, envChecksEnabled, buildTimeoutMinutes, deployTimeoutMinutes, batchOrder, configured,
updatedBy, updatedAt}`；`batchOrder` 机器值 + `batchOrderLabel`（服务端给中文）。

## 4. 页面

- 单元详情新增「流水线配置」块（放在 CODING 绑定之后）：
  - 环境级检查开关（说明三条检查名 + 「停用后新实例不再执行，在途评估立即忽略」）；
  - 构建超时 / 部署超时（数字输入，分钟）；
  - 批次顺序（顺序/逆序，逆序提示「高风险：先高批后低批」）；
  - 保存 + 回显 `updatedBy/updatedAt`；无权限只读并给原因。
- LIB 单元隐藏「环境级检查」（无 VERIFY 节点）与批次顺序（无生产节点），只留构建超时。
- 亮/暗双主题；`scripts/verify-p6c-ui.mjs` + 截图 `unit-pipeline-{light,dark}.png`。

## 5. 测试口径

1. 默认：无行 GET 返回默认 + `configured:false`；保存后回显。
2. 校验：build 4/241 → 40000；deploy 0/121 → 40000；batchOrder 非法 → 40000；UNIT 范围 40009。
3. 环境级检查：关闭后新实例 VERIFY 任务 = 平台启用集合 − 三条 ENV；与平台停用取交集；
   LIB 不受影响（无 VERIFY）。
4. 超时：保存 15/3 后新任务的 `deadline_at` 差值为 15/3 分钟（±容差）；无配置回退 60/8。
5. 批次顺序：REVERSE 后 PROD_DEPLOY 任务的 batch_no 反转（原 1,2,3 → 3,2,1 对应原批）；
   FORWARD 不变；回滚实例行为不受影响。
6. 审计：PUT 一行 `SETTING_UPDATE` object `UNIT`，before/after JSON，scope_unit_id。
7. 权限：无 `setting:manage` 40009；ArchUnit/包规则。
8. 回归：已有流水线测试（P1/P2/P4/P5）全绿。

## 6. 取舍

- **节点启停只开放安全子集**（环境级检查、超时、批次顺序）：不允许关闭 BUILD/TEST_DEPLOY/PROD_DEPLOY
  或改变四节点主干（与 GAP-09 验收一致）。
- **不做可视化节点编排**：节点集合固定，页面只是配置项表单（IA #8 的「节点编排」按只读拓扑 + 配置抽屉呈现）。
- **批次逆序是高风险**：页面二次确认；审计留痕；回滚路径自带逆序，不读该配置。
- **在途实例**：检查开关立即影响评估，超时在任务进入时读取；不做历史任务重算。
- 平台级 `GATE.enabledChecks` 与单元开关同时存在时取交集，语义简单可预期。