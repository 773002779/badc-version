# P4-A 生产回滚（镜像）设计

> 关联：需求 FR-RB-01/02/03、C17、J13；技术方案 §8.5；未实现清单 GAP-01 / GAP-10（回滚入口部分）。
> 范围：**一期仅镜像回滚**；代码回退由团队在 CODING 手工处理，平台只给提示与目标 commit。

## 1. 目标与非目标

**目标**
1. 运维（或 owner 转运维）可把一个部署单元的生产镜像**回滚到当前线上版本之前的某个成功版本**，分钟级止损。
2. 回滚有独立记录（谁、何时、为什么、从哪个版本到哪个版本、结果），可在发布页与全局记录页看到。
3. 回滚不新建发布单、不免审批流程也不走审批；占同一把单元锁；不打断已回滚批次之外的东西。
4. 回滚成功后：来源发布单的变更项 `DONE → ROLLED_BACK`，发布单状态 `ROLLED_BACK`。

**非目标**：代码回退 / revert MR（二期）、按批次选择性回滚（一期整单元反向批次）、跨单元批量回滚、回滚审批。

## 2. 版本清单从哪来

平台没有制品库索引（Q4 未定），一期**从成功生产部署记录推导**：

```sql
SELECT pi.image_digest                    AS digest,
       MAX(nt.finished_at)                AS finished_at,
       ro.publish_no                      AS publish_no,
       ro.executed_by                     AS executed_by,
       COUNT(DISTINCT nt.target_id)       AS batch_count
FROM node_task nt
JOIN pipeline_node     pn ON pn.id = nt.node_id
JOIN pipeline_instance pi ON pi.id = nt.instance_id
JOIN release_order     ro ON ro.id = pi.order_id
JOIN release_item      ri ON ri.id = ro.item_id
WHERE ri.unit_id = #{unitId}
  AND nt.node_type = 'PROD_DEPLOY' AND nt.task_type = 'DEPLOY'
  AND nt.status = 'SUCCESS'
  AND pi.image_digest IS NOT NULL AND pi.trigger_type <> 'ROLLBACK'
GROUP BY pi.image_digest, ro.publish_no, ro.executed_by
ORDER BY MAX(nt.finished_at) DESC
LIMIT 11
```

- 第 1 条 = **当前线上版本**，第 2–11 条 = 近 10 个历史版本；同一 digest 多次部署取最近一次。
- 环境列表（生产/容灾/蓝绿）由该实例成功任务的 DE 环境聚合，返给页面展示。
- **新增 `release_order.executed_by`（V25）**：执行生产时写入操作人，版本清单的「发布人」由此而来；历史数据为空显示 `—`。

## 3. 回滚语义

| 维度 | 规则 |
|---|---|
| 触发 | 单步：`POST /rollbacks` 即执行，要求 `rollback:execute`（运维/管理员）。**取舍**：owner 无该权限时请运维代执行；不引入「发起→待执行」两步（J13：回滚是止损） |
| 前置 | 单元有至少一次成功生产部署；目标 digest ∈ 版本清单且 ≠ 当前线上；单元未被锁；单元已配 `PROD_DEPLOY` 绑定与生产族 DE |
| 单元锁 | 复用 `UnitLockService`：先 `acquire(unitId, 0)`，事务内 `bindInstance`，失败 40006 |
| 实例 | 新建 `pipeline_instance`：`trigger_type=ROLLBACK`、`exec_type=ONLINE`、`order_id=来源发布单`、`image_digest=目标 digest`；节点只有 `PROD_DEPLOY`（seq 0） |
| 批次 | **反向批次**：任务 `batch_no' = maxBatch + 1 - 原 batch_no`，沿用「触发最小未完成批次」的现有机制，于是原最高批（蓝绿/容灾）先跑。C14 的正向是 主生产→主容灾→蓝绿生产→蓝绿容灾，反向即从蓝绿容灾开始 |
| 门禁/封板 | 不做执行前复核；封板期**提示不阻断**（响应带 `freezeWarning`）；不校验窗口 |
| 审批 | 免审批，强制原因（审计在 P4-C 接入） |
| 完成 | 实例 DONE → `rollback_record` 置 SUCCESS；来源变更项若仍 `DONE` → `ROLLBACK` 事件（`ROLLED_BACK`），发布单同步 `ROLLED_BACK`；若变更项已不在 DONE，只记成功并写明「未改变更项状态」 |
| 失败 | 实例 FAILED → 记录 FAILED + 原因；变更项不变；单元锁由编排器按终态释放 |
| 幂等/并发 | 同一单元同时只允许一个回滚（单元锁）；重复提交同一目标在第一次成功后再提交 → 40001（目标已是当前线上） |

## 4. 接口

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | `/api/v1/units/{id}/rollback-versions` | 当前 + 近 10 个历史版本 + 提示语 | 登录 + 单元数据范围 |
| POST | `/api/v1/rollbacks` | `{unitId, targetDigest, reason}` → 立即执行 | `rollback:execute` |
| GET | `/api/v1/rollbacks?unitId=&page=&size=` | 回滚记录分页（时间倒序） | 登录 + 数据范围 |
| GET | `/api/v1/rollbacks/{id}` | 单条记录（含实例号、结果、原因） | 同上 |
| GET | `/api/v1/releases?page=&size=&keyword=&status=&systemId=` | 全局发布记录（GAP-10 列表） | 登录 + 数据范围 |

错误码：40000 单元不存在/目标不在历史/目标等于当前/原因空；40001 无成功生产记录/未到可回滚状态；40006 单元被占用；40009 无权限或越界；40011 单元缺少生产部署配置。

## 5. 执行与完成

1. `RollbackService.execute`：校验 → 读版本清单 → 取来源发布单（当前线上版本对应 order，可能为空）→ `acquire` → 事务：插 `rollback_record` + 建实例 + `bindInstance` → 事务后 `advancer.advance(instanceId)`（进入 PROD_DEPLOY）。
2. `PipelineFactory.createRollback(...)` 生成只有 `PROD_DEPLOY` 的实例与反向批次任务（`TaskType.DEPLOY`，复用现有部署触发/领取/回写机制）。
3. `ProdDeployNodeHandler.start`：当 `instance.triggerType() == ROLLBACK` 时**跳过边界探针**（封板/门禁/执行前复核都不属于回滚），其余批次逻辑不变。
4. `PipelineEventListener`：ROLLBACK 实例的 `PROD_DEPLOY` 节点完成**不映射** `PROD_OK`（否则 `DONE + PROD_OK` 非法、会写一条永远重放的失败记录）。
5. `RollbackCompletionService`（`@EventListener(InstanceFinishedEvent)`，事务内）：按 `instance_id` 找记录；DONE → SUCCESS + 变更项/发布单状态；FAILED → FAILED + 原因。审计与通知在 P4-B/P4-C 接进来。
6. 单元锁释放沿用编排器终态路径，不额外实现。

## 6. 前端

- 发布内容页页头常驻「进入生产回滚」（有 `rollback:execute` 才可点，否则给原因）；抽屉：当前线上版本卡片 + 历史版本单选 + 必读提示（仅镜像、代码人工回退、多单元共库风险）+ 原因输入 + 二次确认。
- 全局「发布记录及回滚」页 `/records`：跨任务发布记录列表（发布单、系统、变更项、状态、计划/完成时间、执行人），行内「回滚」跳发布页；导航新增。
- 回滚记录块：单元详情或全局页展示最近回滚（时间、发起人、目标版本、结果）。

## 7. 测试清单

- 版本清单：0 条（40001）、1 条、11+ 条只取 11、同 digest 合并、排除 ROLLBACK 实例。
- 执行：目标不在历史 40000、目标=当前 40000、原因空 40000、单元被锁 40006、生产配置缺失 40011、无权限 40009。
- 实例：只有 PROD_DEPLOY 节点；批次号反向（原 1,2,3 → 新 3,2,1，首个触发原 3）；`image_digest` = 目标。
- 边界：ROLLBACK 实例不因封板暂停；封板期返回 `freezeWarning` 但仍执行。
- 完成：DONE → 记录 SUCCESS、变更项 `ROLLED_BACK`、发布单 `ROLLED_BACK`；来源变更项非 DONE 时只记成功；FAILED → 记录 FAILED 且变更项不变；PROD_DEPLOY 成功不产生 PROD_OK 的 item failure 行。
- 全局列表：分页、筛选、数据范围。

## 8. 实施拆分（P4-A）

| 任务 | 内容 | 验收 |
|---|---|---|
| R1 后端核心 | V25（`rollback_record` + `release_order.executed_by`）、PO/Mapper（版本 SQL、记录 CRUD）、`RollbackService`（versions/execute/list/detail）、Controller/DTO、权限校验、`ExecuteProdService` 写 `executed_by` | 单元/控制器测试：版本清单与执行前置；无实例执行（R2 接） |
| R2 执行落地 | `PipelineFactory.createRollback`、`ProdDeployNodeHandler` ROLLBACK 分支、`PipelineEventListener` 抑制、`RollbackCompletionService`、Wiring | 批次反向、完成/失败收敛、无假 item failure |
| R3 全局记录 API | `GET /releases` 分页查询（GAP-10） | 分页/筛选/范围测试 |
| R4 前端 | 发布页回滚抽屉、全局记录页、导航、`verify-p4-ui.mjs`、截图 | build + 无头断言 + 0 console error |
| R5 文档 | 技术方案 §8.5 实现对照 + §12.2.4 接口；前端设计 §12；需求实现范围；台账 R39+ | 文档提交与截图 |

## 9. 取舍与残留（写进台账）

- 版本清单无制品库索引，只认「成功生产部署」；未部署过的新镜像无法回滚（这是止损语义，可接受）。
- 回滚单没有「发起人 vs 执行人」分离（单步执行），需求 J13 的「发起 owner/运维」由权限口径解释。
- 同一单元并发回滚由单元锁串行；一次只能滚到一个版本。
- 回滚不作为「发布单」出现在发布记录里，只出现在回滚记录与全局记录的关联里，避免刷屏。
- 通知与审计在 P4-B/P4-C 接入，本阶段只留事件与文案钩子。