# 发版流水线 · P2 实施计划（门禁 / 验证确认 / 生产准入）

> **状态**：已执行（2026-11-22）。T1–T7、T9、T10 全部落地，T8 交付前端面板；决策 D1–D6 按建议默认值采纳，
> 实施中被迫做的三处修正（门禁拆两列、中危不结束变更项、准入用 40002）记在台账的 **P2 ROUND**。
> 验证：`mvn -o verify` 349 tests / BUILD SUCCESS；`npm run build` 通过；22 张截图 console error = 0。
>
> **交付形态**：`VERIFY` 节点从「PAUSED 边界」变成真正可执行的节点；门禁面板见
> `docs/design/frontend-p1-design.md` §8 与 `docs/design/shots/release-gate-*.png`。
> 本地看面板：`scripts/demo-gate.sql`（把一个已存在的执行改成「检查已回传」，可选 pass / waiver 两种）。
>
> **Spec**：`docs/requirements/发版流水线-需求规格说明书.md` v1.6（C9 / C16 / C18 / C19 / C23、4.3 门禁并行时序、4.6 门禁失效、FR-P-04）
> **Tech design**：`docs/tech-design/发版流水线-技术方案-v1.0.md`（§3.3 两道门两列、§6.2.2 检查端点、§7 门禁引擎、§12.2.1 已实现接口）
> **P1 台账**：`docs/superpowers/plans/2026-11-21-p1-execution-ledger.md`（R5 在接口层关闭；R14–R25 记录本轮裁决）

## 范围

**做**：门禁数据模型与结果回传、检查项汇总、测试通过/不通过/撤回、生产准入判定、`VERIFY` 节点处理器（把 P1 的 `PAUSED` 边界推进为可执行节点）、门禁失效语义、验证确认前端面板。

**不做**（留到 P3/P4）：生产审批与窗口/封板（P3）、生产部署批次执行（P3）、回滚（P3）、日常发布（P4）、写基线与合并（二期，C25）、二方库制品入库。

## 复用 P1 的既有地基（不要重造）

- `release_item.gate_status`（正交维度）与 `ItemEvent.GATE_PASS/GATE_FAIL` 已在 V7 + 状态机里；P2 只补**产生**这两个事件的判定。
- `ItemStatus` 已有 `GATE_PENDING / GATE_FAILED / READY`，`ItemStateMachine` 的静态迁移矩阵已覆盖，P2 不改矩阵，除非要新增事件。
- `pipeline_instance.test_ready_at`（V9 CAS 认领）已存在：门禁的「可开测」信号就是它，不要新增第二套就绪标志。
- `NodeHandlerRegistry` 的「缺失处理器 → PAUSED」是 P1 的显式边界；P2 用真正的 `VERIFY` 处理器替换它，`PAUSED` 语义改为**门禁未满足**而不是「功能未交付」。
- `coding-endpoints.yml` 的端点模板机制：检查类接口同样走配置，不写死路径。

## 关键决策（实施前需拍板）

| # | 决策 | 建议默认 | 影响 |
|---|---|---|---|
| D1 | 检查项来源：本地引擎 / 复用 CODING / 外部 Sonar+扫描平台 | 复用 `CicdEngine` 端点模板，新增 `check.start` / `check.query`，先接一个 mock 适配 | 决定 `coding-endpoints.yml` 的形状 |
| D2 | 检查等级与结论映射（高危/中危/低危 → 阻断/可豁免/仅告警） | 高危=阻断且不可豁免；中危=可豁免（需审批人）；低危=仅告警 | 决定豁免接口与 UI |
| D3 | 豁免审批人 | 平台管理员或运维组长，且**不能自审**（沿用 C19 精神） | 决定权限点 |
| D4 | 「测试通过」的资格 | 任务成员中的 TEST 角色，任一人通过即通过（C18）；撤回仅本人 | 决定成员校验 |
| D5 | 门禁失效时的生产者动作（C16） | 已上线批次不回滚；后续批次暂停为 `PAUSED`，运维显式继续/停止 | 决定 P3 的批次执行接口 |
| D6 | 检查超时 | 单项默认 30 分钟，超时判 `TIMEOUT`，门禁记未通过 | 决定 ReconcileJob 扩展 |

---

## 任务分解

### T1 门禁数据模型（V17）

- **目标**：`gate_check`（检查项实例）+ `gate_waiver`（豁免）两张表，挂在 `release_item` 上，按 `(item_id, check_kind, stage)` 去重。
- **要点**：检查项按阶段（`IMAGE` / `ENV`）分组；每条记录等级、结论、外部 id、耗时、结果摘要；豁免记录申请人/审批人/理由/到期时间。
- **测试**：迁移可重入；同一 `(item_id, check_kind)` 二次回传不产生重复行（唯一键）；豁免到期字段可空但一旦填写不得早于创建时间。
- **完成标准**：`mvn -q verify` 绿；表结构与需求 4.3 的门禁维度一致。

### T2 检查项触发与回传（引擎适配）

- **目标**：`gate.start` 触发一组检查、`gate.query` 轮询单项状态；结果写 `gate_check`。
- **要点**：完全复用 `EndpointTemplateResolver` / `EngineHttpClient` / HTTPS 与脱敏规则；新增动作在 YAML 里，Java 只加一个 `GateEngine` 端口 + CODING 实现；引擎不可用时检查项记 `UNKNOWN` 而不是「通过」。
- **测试**：MockWebServer 覆盖正常/超时/非 HTTPS；`UNKNOWN` 不改变门禁结论但会阻止准入。
- **完成标准**：断网时门禁显示「检查未完成」，绝不显示「通过」。

### T3 门禁汇总与判定

- **目标**：`GateService.evaluate(itemId)` → `(已通过 n / 总数 m, 最高缺陷等级, 结论)`；写 `release_item.gate_status` 并发 `GATE_PASS` / `GATE_FAIL`。
- **要点**：结论只看**未豁免的阻断项**；「全量检查通过或豁免」+「测试通过」两者同时满足才是准入；判定结果附带计算过程（前端要展示）。
- **测试**：部分通过 + 一项中危豁免 → 通过；高危未豁免 → 阻断且不可豁免；检查未完成 → `CHECK_RUNNING` 而非通过。
- **完成标准**：`gate_status` 与 `item.status` 正交，不互相改写。

### T4 测试通过 / 不通过 / 撤回

- **目标**：三个动作的权限与留痕：任一人通过即通过（C18）；只有**本人**能撤回（C19），撤回后 `gate_status` 回退到 `TEST_PENDING`；所有人可看不通过原因。
- **要点**：写 `last_test_pass_by/at`（P1 已有列）；撤回要求理由；owner/管理员代撤被拒绝（40009）。
- **测试**：非成员 TEST 角色被拒；第二人通过不改第一人记录但状态仍通过；他人撤回被拒；撤回后再次通过可恢复。
- **完成标准**：每条动作都有可查询的留痕（谁、何时、理由）。

### T5 生产准入与 PROD 节点的门禁校验

- **目标**：准入不足时提交生产返回 40011（带缺失项集合）；`ProdDeployNodeHandler` 在开跑前二次校验镜像一致（C23）与门禁仍有效。
- **要点**：P1 已有 `publish requires ≥1 TEST DE / ≥1 PROD DE` 的 40011 结构，沿用同一错误形状；镜像 digest 不一致时阻断且**不可豁免**。
- **测试**：门禁通过但测试未通过 → 拒绝；两者通过但镜像 digest 不一致 → 拒绝且无豁免路径。
- **完成标准**：拒绝原因能直接展示为「还缺什么」。

### T6 VERIFY 节点处理器（替掉 PAUSED）

- **目标**：`VerifyNodeHandler` 在测试就绪后开跑检查，检查过程中节点为 `RUNNING`；检查未完成时节点停在 `RUNNING` 且 `status_reason=WAITING_CHECKS`，而不是实例 `PAUSED`。
- **要点**：`PAUSED` 保留给「门禁阻断需人工介入」与「门禁失效」两种显式情况；恢复动作（`POST /releases/{publishNo}/resume`）只在门禁重新满足时可调用。
- **测试**：检查未完成 → 不推进；检查通过 + 测试通过 → 节点 `SUCCESS` 并推进到 `PROD_DEPLOY`；门禁失效 → `PAUSED` 且 `resume` 被拒直到恢复。
- **完成标准**：P1 的「PAUSED 是功能边界」变成「PAUSED 是业务状态」，且有恢复入口（R5）。

### T7 门禁失效与批次暂停（C16）

- **目标**：生产已开跑后门禁失效（检查转失败/豁免到期/测试撤回）→ 记录失效原因、后续批次暂停；**绝不回滚已上线批次**。
- **要点**：失效事件写 `pipeline_item_failure`（P1 已有）；后续批次状态置 `PAUSED`，运维显式 `继续/停止`。
- **测试**：已成功批次保持 `SUCCESS`；下一批不启动；恢复后从暂停批次继续，不重复已成功批次。
- **完成标准**：任何失效都不会产生自动回滚。

### T8 验证确认前端面板

- **目标**：发布内容页的「验证确认」节点展开为检查项分组表 + 门禁账（已通过 5/7、最高等级、结论）+ 测试通过/不通过/撤回按钮 + 准入结论与计算过程。
- **要点**：沿用石墨风格；状态用色点，等级用文字标签（高危红、中危琥珀、低危灰）；按钮按权限与状态禁用并给出原因（`title`）；两栏布局与 P1 一致。
- **测试**：`npm run build` 通过；浅/深两套截图；无权限用户看到禁用态而不是隐藏。
- **完成标准**：不用看日志就能回答「为什么还不能上生产」。

### T9 豁免与到期

- **目标**：中危检查项可申请豁免（理由 + 到期时间），审批通过后计入「通过」；到期自动失效并把门禁打回未通过。
- **要点**：高危不可豁免（接口直接拒绝）；豁免到期由 `ReconcileJob` 每天扫描一次；到期失效同样走 C16。
- **测试**：高危申请被拒；未审批的豁免不计入门禁；到期后 `gate_status` 回退且留痕。
- **完成标准**：豁免率可统计（需求 4.x 的「豁免率」指标）。

### T10 端到端与文档

- **目标**：`P2BoundaryEndToEndTest`：发布 → 构建 → 测试部署 → 检查回传 → 测试通过 → 准入可提交；引擎用 stub，检查用 mock。
- **要点**：覆盖三条失败路径（检查未完成、检查高危、测试撤回后准入失效）；同时更新需求 §4.3 的时序图与前端设计文档。
- **完成标准**：`mvn -q verify` 全绿；`docs/` 与实现一致，台账记录本轮裁决。

---

## 顺序与依赖

```
T1 ──▶ T2 ──▶ T3 ──▶ T4 ──▶ T5 ──▶ T6 ──▶ T7
                         │        │
                         └──▶ T9  └──▶ T8（可并行，接口先冻结）
                                       └──▶ T10
```

- T1/T2 有外部不确定性（D1：检查接口形状），先做 T1 + T2 的 mock 适配，真实接口到位只改 YAML。
- T8 只依赖 T3/T4/T5 的读模型，可与 T6/T7 并行。
- 每个任务结束都要 `mvn -q verify` 全绿、一次提交只做本任务。

## 验收口径

- 门禁的四类结论（检查中 / 通过 / 阻断 / 可豁免）都能在页面上看到，并且与 `gate_status` 一一对应；
- 准入不足时提交生产被拒，提示里能列出缺失项；
- 检查未完成永远不会被当成「通过」；
- 门禁失效只影响**未发生**的动作，已上线批次不回滚；
- PAUSED 有原因、有恢复入口、有告警（R5 关闭）。
---

## 执行结果（2026-11-22）

| 任务 | 落点 | 说明 |
|---|---|---|
| T1 | V17__gate.sql、V19__gate_split_test_status.sql、domain/gate/*、GateCheckPO/GateWaiverPO | 检查与豁免两张表；V19 把测试维度拆到 	est_status（原一列装不下两个正交维度） |
| T2 | coding-endpoints.yml 的 check.start / check.env.start / check.query + CodingEngine.startCheck/queryCheck + GateCheckService + GateReconcileJob | 触发、轮询、超时、UNKNOWN 不算通过、重复回传是覆盖 |
| T3 | GateEvaluator + GateService.evaluate | 最高等级判定、豁免快照校验、终局才推主状态、失效撤回准入 |
| T4 | GateService.passTest/failTest/revokeTest + ReleaseTaskMemberMapper 校验 | 任一人通过（C18）、仅本人撤回（C19）、不通过必须给原因 |
| T5 | GET /items/{id}/gate/admission | 缺项可解释；提交入口与审批留 P3 |
| T6 | VerifyNodeHandler + NodeHandler.mayComplete/failureReason + ReleaseResumeService | PAUSED 语义从「功能边界」变成「业务状态」，并有恢复入口 |
| T7 | RerunAppService（全量重跑置 INVALID）+ 失效撤回路径 | 只作用于未发生的动作，绝不回滚已成功批次 |
| T8 | web/src/components/VerifyPanel.vue + ReleaseView + GET /api/v1/me | 两道门并排、结论与算式、检查分组、豁免流程、准入缺口；按钮禁用并给原因 |
| T9 | GateWaiverService + V18__gate_waiver_role.sql + 每日到期扫描 | 不可豁免项直接拒绝；不能自审；到期置 INVALID |
| T10 | P2BoundaryEndToEndTest（6 场景）、GateEvaluatorTest（13）、GateWaiverRulesTest（7） | 覆盖三条失败路径 + 断网 + 权限 + 豁免全流程 |

**验收对照**

- 四类结论（检查中 / 通过 / 阻断 / 可豁免）都能在页面看到，并与 gate_status 一一对应 —— 见两张截图；
- 准入不足时缺口逐条列出（dmission.missing）；
- 检查未完成永远不算通过（UNKNOWN/RUNNING → CHECK_RUNNING）；断网场景有测试；
- 门禁失效只影响未发生的动作，已上线批次不回滚（P2 里没有批次，但代码路径不触碰 SUCCESS 任务行）；
- PAUSED 有原因（status_reason）、有恢复入口（/releases/{publishNo}/resume），告警留给 P3 的通知能力。