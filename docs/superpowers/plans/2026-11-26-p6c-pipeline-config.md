# P6-C 流水线配置实现计划

契约：`docs/superpowers/specs/2026-11-26-p6c-pipeline-config-design.md`。

## Task 1 — 后端

1. V31 `unit_pipeline_config`；`infra/repo/{UnitPipelineConfigMapper,UnitPipelineConfigPO}`。
2. `application/PipelineConfigService`（GET/PUT、校验、默认值、审计）；`web/controller/PipelineConfigController`
   （或并入 UnitController）+ `web/dto/PipelineConfigDtos`。
3. 效果：
   - `PipelineFactory.createOnline`/`createRerun`/`createDaily` 接受配置（P6-B 已有的 enabledChecks 参数
     扩展为「平台集合 ∩ 单元 env 开关」；batchOrder 只作用于新建在线实例的生产族任务）；
   - `PipelineRepository.pipelineConfigOf(instanceId)` + `MybatisPipelineRepository` 实现；
   - `BuildNodeHandler`/`LibPublishNodeHandler`/`DailyDeployNodeHandler`/`TestDeployNodeHandler`/
     `ProdDeployNodeHandler` 用配置超时（回退常量）；
   - `GateService`/`LibSecurityNodeHandler` 的启用集合并入单元 env 开关。
4. 测试 `server/src/test/java/com/sf/release/settings/`（或 `pipelineconfig/`）：设计 §5 全条 + 回归。

硬约束：不开放节点主干变更；回滚不读该配置；在途超时不重算；全量测试绿（基线 909）；
提交一次 `feat: P6-C unit pipeline config backend`。

## Task 2 — 前端

单元详情「流水线配置」块（LIB 隐藏检查/批次项）+ 只读态 + 二次确认；探针 + 截图；
`npm run build` 绿。

## Task 3 — 文档

技术方案 §8.15 + §12.2.12；前端设计 §20；未实现清单 GAP-09 完成 + 残留；需求规格 §10；台账。

## 门禁

Task 1 → lead 评审（交集语义、超时回退、逆序影响面、审计范围）→ Task 2 → 联调 → Task 3。