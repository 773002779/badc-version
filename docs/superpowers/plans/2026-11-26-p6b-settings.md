# P6-B 设置补齐实现计划

契约：`docs/superpowers/specs/2026-11-26-p6b-settings-design.md`。

## Task 1 — 后端

1. V30 `app_setting` + 三个种子段（JSON）。
2. `application/settings/`：`SettingsService`（读/写三段、类型化视图、校验、审计）、
   `ApplicationSettings` 视图记录；`infra/repo/{AppSettingMapper,AppSettingPO}`。
3. 效果接线：
   - `PipelineFactory.createOnline(..., Set<CheckType> enabled)` 重载（旧签名 = 全开）；
     `PublishAppService` 读配置传入；
   - `GateService` 评估前按配置过滤 `gate_check` 行；
   - `ApprovalSettings` 保存时替换 `user_role` 的两个角色行（校验用户 ACTIVE）；
   - `UnitLeaseRenewJob` 每轮读 `pauseBoundHours`（读失败回退 env）。
4. `web/controller/SettingsController` + `web/dto/SettingsDtos`（6 个端点，`setting:manage`）。
5. 测试 `server/src/test/java/com/sf/release/settings/`：设计 §5 全条；含 renew job 动态读取、
   LIB/UNIT 两条工厂路径、审批即时生效、审计与权限。

硬约束：不改锁 TTL；不建字典/角色权限页；通知/窗口设置不动；全量测试绿（基线 892）；
提交一次 `feat: P6-B platform settings backend`。

## Task 2 — 前端

设置页三块（门禁规则/审批人名单/锁超时）+ 只读态 + 二次确认；`verify-p6b-ui.mjs` + 截图；
`npm run build` 绿。

## Task 3 — 文档

技术方案 §8.14 + §12.2.11；前端设计 §19；未实现清单 GAP-08 完成 + 未做项（字典/角色权限/TTL）；
需求规格 §10 一句；台账 R90+。

## 门禁

Task 1 → lead 评审（配置即时性、审批名单替换范围、评估过滤、锁读取回退）→ Task 2 → 联调 → Task 3。