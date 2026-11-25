# P6-G 单 DE 重部署与日志设计（PART-04）

> 需求 PART-04：单 DE / 单环境重新部署 + 日志代理；检查作废规则沿用 §4.6。
> 一期流水线失败即整单 `FAILED`（节点/实例终态），因此「单 DE 重部署」实现为**只部署该 DE 的一次重跑实例**
> （复用 P1 的 `RERUN_FROM_NODE` 机制），而不是在原实例内改行。

## 1. 语义

- 入口：发布内容页的**生产部署任务行**（`node_type=PROD_DEPLOY` 且 `task_type=DEPLOY`）：
  「重新部署」与「日志」两个动作；构建/测试/入库任务行只有「日志」。
- 允许重部署的条件（全部满足）：
  1. 该任务是**最新实例**（`MAX(pipeline_instance.id)` of order）里、该 DE 的最新 attempt；
  2. 任务状态为 `FAILED`/`TIMEOUT`（成功的批次不提供单 DE 重部署，避免语义歧义）；
  3. 发布单/变更项处于 `FAILED` 或 `PROD_DEPLOYING`；发布单未被关闭/终止；
  4. 调用者具 `control:execute-prod` + 单元范围。
- 执行（一个事务 + 锁后启动）：
  1. 取单元锁（与发布/重跑同款，40006 冲突）；
  2. 新建 `RERUN_FROM_NODE` 实例，`fromNode=PROD_DEPLOY`，`image_digest/commit_sha` 沿用上一实例；
     **仅目标 DE 的任务保留 PENDING，其所在批次的其他 DE 任务也保留**（同批次要么整批重试，避免半批），
     其余批次与更早节点 `SKIPPED`；
  3. 变更项 `EXECUTE_RERUN → PROD_DEPLOYING`（现有事件/状态机，无需新事件）；
  4. 失效该变更项的检查结果（`GATE_CHECK` 未完成行 `INVALID`，`release_item.gate_status=CHECK_INVALID`），
     与用户「重跑」规则一致（§4.6）——重部署会重新镜像/环境，旧检查不再代表当前对象；
  5. 审计 `RERUN`（ORDER，summary「单 DE 重部署（目标 <deName>）」）；通知沿用重跑无通知（FR-N-01 未列）；
  6. 提交后 `orchestrator.advance(newInstanceId)`。
- 不允许的调用：非 DEPLOY 任务、历史实例任务、成功任务、DONE/CLOSED/TERMINATED 发布单 → 40001；
  锁冲突 40006；无权限/越界 40009；任务不存在 40000。

## 2. 日志代理

- `GET /api/v1/releases/{publishNo}/tasks/{taskId}/logs?offset=&limit=`：
  - 校验任务属于该发布单的最新实例（或历史实例允许查看，需 `instanceId` 参数？一期**只对最新实例**开放）
    + 单元范围；
  - `kind` 由任务类型决定：`CODE_CHECK/PKG_BUILD/IMG_BUILD` → `BUILD`；`DEPLOY` → `DEPLOY`；
    `LIB_PUBLISH` → `PUBLISH`；检查类任务无外部作业 → 40001（页面不显示日志按钮）；
  - 调用 `CicdEngine.logs(externalId, kind, offset, limit)`；无 external_id → 40001；
    引擎不可用 → 40001/引擎错误原样；返回 `{content, truncated, offset, limit}`。
  - `limit` 1–8000（引擎侧上限），offset ≥0；越界 40000。

## 3. 接口与页面

| 方法 | 路径 | 权限 |
|---|---|---|
| POST | `/api/v1/releases/{publishNo}/tasks/{taskId}/redeploy` | `control:execute-prod` + 范围 |
| GET | `/api/v1/releases/{publishNo}/tasks/{taskId}/logs?offset=&limit=` | 登录 + 范围 |

- 发布页节点任务行：`重新部署`（仅符合条件时可用，二次确认说明「同批次一起重试、检查将失效」）、
  `日志`（抽屉：等宽文本 + 加载更多/刷新；引擎不可用显示服务端文案）。
- 不新增页面；亮/暗双主题；`scripts/verify-p6g-ui.mjs` + 截图 `redeploy-{light,dark}.png`。

## 4. 测试口径

1. 允许：最新实例 FAILED 的 DEPLOY 任务 → 新实例 `RERUN_FROM_NODE`、`fromNode=PROD_DEPLOY`、
   仅目标批次 PENDING（同批其他 DE 一起 PENDING）、其余批次 SKIPPED、镜像/commit 沿用；
   变更项 `PROD_DEPLOYING`；检查 `INVALID`；锁占用后启动；审计 `RERUN`。
2. 拒绝：成功任务 40001；非 DEPLOY 任务 40001；历史实例任务 40001；DONE/CLOSED/TERMINATED 40001；
   锁冲突 40006；无权限/越界 40009；任务不存在 40000。
3. 日志：BUILD/DEPLOY/PUBLISH 三种 kind 正确；offset/limit 透传；无 external_id 40001；
   引擎异常原样；历史实例任务拒绝；越界参数 40000；scope 40009。
4. 前端探针：重部署按钮的条件显示/禁用原因、确认框文案、新实例生成后页面跳转/刷新、日志抽屉内容与
   引擎不可用文案、0 console error。
5. 回归：既有 rerun/发布/回滚测试全绿。

## 5. 取舍

- **只对最新实例**：历史实例是只读证据；重部署当前失败才有意义。
- **同批整批重试**：单个 DE 失败往往同批共享配置；只重试一个 DE 会造成批内版本不一致。
- **成功任务不重部署**：要重新上线用「重新发布/回滚」，避免把「单 DE 重部署」变成绕过准入的通道。
- **重部署沿用上一实例镜像**，不重新构建（与 J17 重跑同口径）；需要新镜像走整单重跑。
- **日志只代理最新实例**：历史日志仍在 CODING 侧可通过外链查看，页面不重复实现历史查询。