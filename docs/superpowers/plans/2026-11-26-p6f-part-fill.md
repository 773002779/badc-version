# P6-F 页面补齐实现计划（PART-02/03/06）

契约：`docs/superpowers/specs/2026-11-26-p6f-part-fill-design.md`。

## Task 1 — 后端

1. `ReleaseDetail` 增加 `testers/deployers`（`{userId,name}`）：`ReleaseQueryService` + member/user join
   + `ReleaseDtos` + 测试（角色过滤、name、空数组）。
2. 豁免默认：`GateWaiverService.request` 的 `expireAt` 可空 → 默认 `WAIVER.defaultDays`（默认 7，1–90）
   → 计算 `now+days`；显式值行为不变；测试（默认/配置/上限/早于当前/审计）。
3. `SettingsService` 增加 `waiver()`/`saveWaiver()` + `GET/PUT /api/v1/settings/waiver`（V34 只加
   `app_setting` 的 `WAIVER` 段种子，无新表）；审计、40000、40009、读失败回退 7。
4. 测试 `server/src/test/java/com/sf/release/partfill/`（或复用 `settings/`）覆盖设计 §5 的 1–3、5、6。

硬约束：不新增批量端点；不改批量/单发行为；全量测试绿（基线 994）；
提交一次 `feat: P6-F part fill backend`。

## Task 2 — 前端

1. 任务详情变更项多选 + 「批量部署」（串行、逐项成败、结果摘要、可发布状态过滤）。
2. 发布页人员列（测试人员/部署人员）。
3. 设置页门禁块内「豁免默认有效期（天）」+ 豁免对话框有效期可空（提示默认值）。
4. `scripts/verify-p6f-ui.mjs` 两主题 + 截图 `batch-deploy-{light,dark}.png`、`release-roles-{light,dark}.png`。
   `npm run build` 绿。

## Task 3 — 文档

技术方案 §8.18 + §12.2.15；前端设计 §23；未实现清单 PART-02/03/06 标完成 + 未做（PART-01/04/05/07）；
需求规格 §10 一句；台账 R121+。

## 门禁

Task 1 → lead 评审（人员列过滤、豁免默认/上限、设置回退）→ Task 2 → 联调（真实批量发布两项、豁免留空）
→ Task 3。