# PART-05 CODING 多连接与部署目标映射 · 实施计划（2026-11-27）

设计：`docs/superpowers/specs/2026-11-27-p05-coding-multi-conn-design.md`

| 任务 | 内容 | 验收 |
|---|---|---|
| Task 1 后端 | 连接解析（provider.forUnit + engine start 类 + CodingJobQueryService 显式连接）、部署映射（DeployTarget/DeployCmd/handlers/yml）、`CodingController` subsystemId 参数；测试 `part05/`。 | 全量 `mvn -o test` 绿 |
| Task 2 前端 | `SubsystemDetailView` 「CODING 连接覆盖」块（get/put ?subsystemId）、`UnitDetailView` DE 表单加 `codingDeployTarget`；api 封装。 | `npm run build` 绿 |
| Task 3 探针 | `scripts/verify-p05-ui.mjs`（系统详情覆盖块渲染/保存/读回 + 恢复；DE 表单字段）双主题 + 截图。 | 探针双主题绿 |
| Task 4 文档 | 未实现清单 PART-05 标完成；技术方案 §8.22/§12.2.18；前端设计 §27；台账 R142+。 | 与提交一致 |

提交：`feat: PART-05 coding multi-connection and deploy target mapping`（backend）、
`feat: PART-05 web connection override`（前端 + 探针）、`docs: PART-05 closeout`（或合并）。
