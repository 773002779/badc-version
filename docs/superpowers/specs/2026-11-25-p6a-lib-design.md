# P6-A 二方库制品与发布流水线设计（GAP-07）

> 需求 FR-R-03、FR-P-09、C13、FR-T-04、C24（二方库无日常）；Q4（制品库归属）未定，本阶段只落
> 坐标、检测与入库任务，不接具体私服。

## 1. 语义

- 二方库 = `deploy_unit.type='LIB'`：与部署单元同构，但**无部署/环境配置**，发布产出制品，不进入生产准入。
- 制品坐标：`groupId`、`artifactId`；版本沿用变更项 `version`（由构建产出/校验，不在本切片生成规则引擎）。
- 依赖影响面：`GET /api/v1/libs/{libUnitId}/dependents` 返回「同系统内 module_path 等于该库 artifactId
  的部署单元」。**这是文档化的启发式**（Q4/CODING 依赖 API 未定），接口带 `source: "MODULE_PATH"` 字段，
  未来替换实现不改契约。
- LIB 流水线（独立编排，3 节点）：
  1. `BUILD`：`CODE_CHECK → PKG_BUILD`（**不含 IMG_BUILD**，二方库不产镜像）；
  2. `LIB_SECURITY`：三条制品相关检查 `SONAR`、`COMPONENT_VULN`、`VERSION_DEFECT`
     （C13「先检测」；不通过/未豁免 → 终止，不入库）；
  3. `LIB_PUBLISH`：一条 `LIB_PUBLISH` 任务，调用引擎上传制品（C13「通过后入库」）。
- 完成口径：`LIB_PUBLISH` 成功 → 变更项 `DONE`（FR-T-04，完成率已按 DONE 计数）；失败 → `FAILED`。
- **无**：测试部署、生产部署、写基线、日常入口、回滚候选、门禁的「测试通过」维度。

## 2. 模型（V29）

```sql
ALTER TABLE deploy_unit
  ADD COLUMN artifact_group_id    VARCHAR(128) NULL COMMENT 'LIB: Maven groupId',
  ADD COLUMN artifact_artifact_id VARCHAR(128) NULL COMMENT 'LIB: Maven artifactId';
```

- 枚举追加（末尾，不改既有值）：`NodeType.LIB_SECURITY`、`NodeType.LIB_PUBLISH`；
  `TaskType.LIB_PUBLISH`；`TaskKind.LIB_PUBLISH`（CODING 绑定，LIB 专用）；`ExecType.LIB`。
- `ItemEvent.LIB_OK`（`BUILDING → DONE`）、`ItemEvent.LIB_FAIL`（`BUILDING → FAILED`）加入状态机；
  LIB 项的 `BUILD` 节点成功**不**触发 `BUILD_OK→TESTING`——监听器按 `item.type==LIB` 分支，只映射
  `LIB_SECURITY`/`LIB_PUBLISH` 的结果。
- 无新表；`deploy_unit.type`/`module_path` 已存在。

## 3. 工厂与处理

- `PipelineFactory.createLib(item, unitId, envDes 忽略, now)`：三节点；`order_id` 用真实发布单
  （LIB 仍是上线变更项，**占单元锁**，防止同一库的制品版本并发冲突）；实例 `execType=LIB`、
  `trigger=INITIAL`、`currentNode=BUILD`。
- `LibSecurityNodeHandler`：进入时对三项检查调用 `GateCheckService.refresh(itemId, kind, instanceId)`
  （幂等，已有检查记录则跳过）；`mayComplete` 当三项结果均为 `PASS` 或 `WAIVED`（P2 既有豁免规则）；
  `failureReason` 当任一 `FAIL` 且无有效豁免。检查结果仍落 `gate_check`，**不**走 VERIFY/生产准入。
- `LibPublishNodeHandler`：解析 `LIB_PUBLISH` 绑定（缺失 40001），调用新 SPI
  `CicdEngine.startPublish(PublishCmd)`，置 `WAITING_EXTERNAL` + 60min；完成后由 webhook/对账推进。
  引擎适配器 `CodingEngine` 用新端点 action `publish.start`（application.yml 增加占位模板，
  变量：`taskId/groupId/artifactId/version/pipelineNo/attempt`）；无连接时按 `ENGINE_UNAVAILABLE` 失败。
- `PublishAppService.publish`：`item.type` 为 LIB 时走 LIB 分支（校验坐标、BUILD/LIB_PUBLISH 绑定、
  单元锁、发布单号），UNIT 路径不变。

## 4. 接口与页面

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/libs/{unitId}/dependents` | 登录 + 范围 | `{libUnitId, artifactId, source, dependents:[{unitId,name,modulePath}]}`；非 LIB → 40001 |
| POST | `/api/v1/items/{itemId}/publish` | `release:publish` | LIB 复用发布入口（响应多了 `type`） |

- 单元创建/编辑（LIB）：`artifact_group_id`/`artifact_artifact_id` 必填，格式 `^[A-Za-z0-9._-]{1,128}$`；
  UNIT 忽略该字段。`UnitView` 增加这两个字段（可空）。
- 页面：单元详情 LIB 分支显示「制品坐标」「依赖单元」块；发布内容页对 LIB 隐藏生产准入/测试人员，
  显示三节点进度与「豁免入库」标记（豁免到期不自动下架，只标记）。

## 5. 测试口径

1. 坐标校验（LIB 必填/格式；UNIT 忽略）；dependents 命中/非 LIB 40001/范围。
2. 工厂：LIB 三节点、BUILD 只有两任务、无 PROD_DEPLOY/VERIFY。
3. 状态：BUILD 成功不改状态；LIB_SECURITY 通过后仍 BUILDING；LIB_PUBLISH 成功 → DONE；
   检查失败 → FAILED（含原因）；豁免后通过。
4. 处理：security handler 触发三次检查且幂等；publish handler 调 `startPublish` 一次并 WAITING_EXTERNAL；
   引擎失败 → 任务/实例 FAILED 且原因落库。
5. 发布入口：LIB 走 LIB 分支（不建四节点）；缺坐标/绑定 40001；非 LIB 行为不变（回归）。
6. 完成率：DONE 计入；无测试/生产状态。
7. 审计/通知：LIB 发布写 `PUBLISH` 审计；完成/失败是否发通知本切片**不发**（FR-N-01 未列二方库），记录取舍。
8. ArchUnit/包规则；controller 测试不在 `..web..`。

## 6. 取舍

- **依赖影响面是启发式**（module_path == artifactId），Q4 未定、无依赖接口；接口契约保留 `source` 以便替换。
- **不接制品库**：入库任务由引擎执行，本平台只记录任务与结果；Q4 确定后补制品详情/下载链接。
- **版本规则不做**：版本仍取变更项 `version`，不自动递增/校验重复版本（需制品库元数据）。
- **不做 LIB 日常/回滚**（需求明确无）；LIB 变更项可被关闭/归档（P5-B 已支持）。
- 真实 CODING `publish.start` 端点未验证（本机无连接），以 fake engine 测试 + `ENGINE_UNAVAILABLE` 落库为准。