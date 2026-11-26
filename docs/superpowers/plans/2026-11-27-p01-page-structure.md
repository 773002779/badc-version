# PART-01 页面信息结构 · 实施计划（2026-11-27）

设计：`docs/superpowers/specs/2026-11-27-p01-page-structure-design.md`

| 任务 | 内容 | 验收 |
|---|---|---|
| Task 1 后端 | `UnitReleaseSummaryService` + `GET /api/v1/units/{unitId}/release-summary`（@DataScope + ScopeGuard；复用 `RollbackRecordMapper.selectCurrent` + 锁行读）；测试 `part01/UnitReleaseSummaryTest`（latest/lock/null、40000/40009）。 | 全量 `mvn -o test` 绿 |
| Task 2 前端 | 1) `getUnitReleaseSummary` API + 类型；2) `UnitListView` 三列（对当前页并发调 summary）；3) `SubsystemDetailView` 「发布记录」块 + 「管控配置」入口；4) `ControlView` 读 `route.query.subsystemId` 预选。 | `npm run build` 绿 |
| Task 3 探针 | `scripts/verify-p1b-ui.mjs` 双主题 + 截图 `unit-summary-*`、`subsystem-records-*`。 | 探针双主题绿 |
| Task 4 文档 | 未实现清单 PART-01 标完成（含两条已接受残留）；技术方案 §8.21/§12.2.17；前端设计 §26；台账 R139+。 | 与提交一致 |

提交：Task 1 `feat: PART-01 unit release summary backend`；Task 2/3 `feat: PART-01 web page structure`；
Task 4 `docs: PART-01 closeout`（或按实际情况合并，写明阶段编号）。
