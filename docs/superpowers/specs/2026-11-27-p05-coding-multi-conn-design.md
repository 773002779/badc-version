# PART-05 CODING 多连接与部署目标映射设计

> 需求出处：未实现清单 §2 PART-05——只有全局连接；部署目标用 DE 名称。验收：按系统覆盖连接（引擎解析顺序
> GLOBAL→SUBSYSTEM）；DE→CODING 环境参数显式映射。

## 1. 语义与裁决

1. **连接解析（GLOBAL→SUBSYSTEM）**：
   - 现状：`coding_conn` 表/域/`CodingConnAppService` 已支持 `(scope, subsystem_id)` upsert，但引擎调用只查
     GLOBAL 行（`GlobalCodingConnectionProvider`）。
   - 改造：provider 升级为**按 unitId 解析**——`forUnit(unitId)`：`unitId>0` 时查
     `deploy_unit.subsystem_id`，再查该系统的 `SUBSYSTEM` 覆盖行，无覆盖回退 GLOBAL；`get()` 保持 GLOBAL。
     仍按调用逐次读取（lazy/凭证轮转语义不变）。
   - **适用范围（裁决）**：带 unitId 的 start 类调用（`triggerBuild` / `triggerDeploy` / `startCheck` /
     `startPublish`）与单元表单的 `listJobs`（`CodingJobQueryService` 走显式连接重载）按子系统解析；
     **`query` / `logs` / `cancel` 只有 externalId、无 unitId，保留 GLOBAL 连接**——已接受残留：若某子系统用独立
     token，其作业的轮询/日志/取消走全局连接可能 401（记录在未实现清单 §6）。
2. **部署目标映射（DE→CODING 环境参数）**：
   - `env_de.coding_deploy_target` 列与 API 字段已存而不用；部署链 `DeployTarget`/`DeployCmd` 只带 `deName`。
   - 改造：`PipelineRepository.DeployTarget` 增加 `codingDeployTarget`；`DeployCmd` 增加该字段；三个部署
     handler（Test/Prod/Daily）构造 cmd 时传值——**为空回退 `deName`**（一期模板只有一个目标字段，未配置的 DE
     行为与今天完全一致）；`CodingEngine.triggerDeploy` 把它作为 `codingDeployTarget` 变量放入模板；
     `coding-endpoints.yml` 的 `deploy.start` body 加 `"CodingDeployTarget":"{codingDeployTarget}"`
     占位符（同步注释里的变量清单）。
3. **HTTP 入口按系统**：`CodingController` 的 `GET/PUT /api/v1/coding/conn` 加可选 `?subsystemId=`（空 =
     全局；`CodingConnAppService.save("SUBSYSTEM", subsystemId, ...)` 后端已支持）。**前端入口放在系统详情页**
     （「CODING 连接覆盖」块，`setting:manage` 才可读写——每个系统维护自己的覆盖，语义与该页既有的
     「CODING 项目」编辑一致）；设置页的全局连接表单不变。
4. **DE 表单**：单元详情「环境 DE」添加表单加 `codingDeployTarget` 输入（`EnvDeView` 已回传该字段，展示列
     同步显示）。

## 2. 改动清单

| 层 | 改动 |
|---|---|
| infra/engine | `GlobalCodingConnectionProvider` → `forUnit(long)`；`CodingEngine` start 类方法用 `provider.forUnit(cmd.unitId())`（构造改收 provider；`Supplier` 路径保留给测试） |
| infra/config | `EngineConfig` 注入 provider |
| domain | `DeployCmd` 加 `codingDeployTarget`；`PipelineRepository.DeployTarget` 加字段 |
| handler | `TestDeployNodeHandler` / `ProdDeployNodeHandler` / `DailyDeployNodeHandler` 传值（空回退 deName） |
| resources | `coding-endpoints.yml` deploy.start 加占位符 |
| web | `CodingController` 加 `subsystemId` 参数；`CodingDtos` 响应加 `subsystemId`/`scope` |
| 前端 | `api`：`getCodingConn(subsystemId?)` / `saveCodingConn(body, subsystemId?)`；`SubsystemDetailView` 「CODING 连接覆盖」块；`UnitDetailView` DE 表单加字段 |
| 测试 | `part05/CodingConnectionResolutionTest`（优先级/回退/未知单元）；部署映射断言（triggerDeploy 的 body 变量，复用既有引擎测试模式） |

## 3. 测试口径

1. 连接解析：造 GLOBAL + SUBSYSTEM 两行 + 单元 → `forUnit` 返回子系统连接；删子系统行 → 回退 GLOBAL；无
   GLOBAL → null（引擎走 ENGINE_UNAVAILABLE 路径）。`PUT /coding/conn?subsystemId=` 保存后 `GET` 读回。
2. 映射：`triggerDeploy` 的请求 body 含 `"CodingDeployTarget":"<mapping>"`（配置了映射的 DE 用映射值、未配置
   的用 deName）——用 MockWebServer/既有引擎测试基建。
3. 前端：`npm run build` 绿；探针 `scripts/verify-p05-ui.mjs` 双主题断言系统详情「CODING 连接覆盖」块（渲染/
   保存/读回、`finally` 删除覆盖行恢复）与 DE 表单新字段；0 console error；截图 `coding-override-{light,dark}.png`。

## 4. 文档收口

未实现清单 PART-05 标完成（query/logs/cancel 只走 GLOBAL 记为已接受残留）；技术方案 §8.22/§12.2.18；
前端设计 §27；台账 R142+。
