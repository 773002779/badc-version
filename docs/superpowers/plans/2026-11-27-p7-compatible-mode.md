# PART-07 兼容模式语义 · 实施计划（2026-11-27）

设计：`docs/superpowers/specs/2026-11-26-p7-compatible-mode-design.md`

| 任务 | 内容 | 验收 |
|---|---|---|
| Task 1 前端 | 1) `ReleaseView.vue`：非 LIB 时 best-effort 读 `getUnit(detail.unitId)`，`branchMode==='COMPATIBLE'` 渲染头部 info 横幅（§2 文案）；2) `RollbackDialog.vue`：打开时读 unit，兼容单元在抽屉内追加提示，不阻断回滚。 | `npm run build` 绿 |
| Task 2 探针 | `scripts/verify-p7-ui.mjs`：种子 `demo-redeploy.sql`，探针内把 `deploy_unit`（unit 2）置 `COMPATIBLE`、`finally` 还原；双主题断言发布页横幅 + 回滚抽屉提示出现、STANDARD 无横幅、0 console error；截图 `compatible-{light,dark}.png`（`git add -f`）。 | 探针双主题绿 |
| Task 3 文档 | 未实现清单 PART-07 标完成（语义 + 不删字段裁决）；技术方案 §8.20；前端设计 §25；台账 R137+。 | 与提交一致 |

提交：`feat: PART-07 compatible mode semantics`（前端 + 探针 + 文档一并提交；后端零改动，全量测试无需重跑，但
提交前跑一次 `mvn -o test` 回归确认仍 1038 绿）。
