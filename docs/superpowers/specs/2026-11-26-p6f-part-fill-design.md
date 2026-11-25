# P6-F 页面补齐（批量部署 / 人员列 / 豁免默认）设计

> 覆盖 PART-02、PART-03、PART-06；均为「有入口/有字段但缺能力」的收尾切片，不改流水线主干。

## 1. PART-02 批量部署（前端为主）

- 任务详情的变更项表格支持多选（仅 `PENDING/FAILED/GATE_FAILED/REJECTED` 等可发布状态可勾选；
  `DONE/CLOSED/TERMINATED/ROLLED_BACK` 与已归档任务禁选）。
- 「批量部署」按钮：对选中项**逐个**调用现有 `POST /api/v1/items/{itemId}/publish`（串行，避免同一单元
  并发锁冲突时互相踩）；每项独立成败：
  - 成功 → 记录 publishNo/pipelineNo；
  - 失败（40006 锁被占、40009 无权限、40001 状态/配置）→ 记录错误文案，**不中断**后续项；
  - 结束后弹结果摘要（成功 N / 失败 M + 失败项与原因），刷新列表。
- 不做后端批量端点：现有发布接口已具备单次全部校验与审计；批量是 UI 编排。
- 勾选项含 LIB 时同样走 publish（P6-A 已支持 LIB）。

## 2. PART-03 发布页人员列（后端 + 前端）

- `ReleaseDetail` 增加 `testers`/`deployers` 两个数组：任务成员中 `role=TEST` / `role=OPS` 的用户
  （含 `name`，join `app_user`），按 user_id 排序；无则空数组。
- 发布内容页页头/信息区显示「测试人员」「部署人员」（多个人顿号分隔；空显示 `—`）。
- 数据只读，不参与权限判断（真正权限仍由 `release:*`/`control:*` 决定）。

## 3. PART-06 豁免默认有效期（后端 + 前端）

- `POST /api/v1/items/{itemId}/waiver` 的 `expireAt` 改为**可空**：为空时用配置的默认天数
  （默认 **7 天**）计算 `now + defaultDays`；显式值仍校验「晚于当前 + ≤90 天」。
- 配置：`app_setting` 增加 `WAIVER {"defaultDays":7}`（1–90）；`GET/PUT /api/v1/settings/waiver`
  （`setting:manage`，审计 `SETTING_UPDATE` object `WAIVER`）；设置页「门禁规则」块底部加一个
  「豁免默认有效期（天）」输入。
- 前端豁免对话框：有效期改为可空（留空 = 默认 N 天，提示当前默认值），显式日期仍可填。

## 4. 接口与兼容

| 方法 | 路径 | 变化 |
|---|---|---|
| GET | `/api/v1/releases/{publishNo}` | 响应新增 `testers[]`/`deployers[]`（`{userId,name}`） |
| POST | `/api/v1/items/{itemId}/waiver` | `expireAt` 可空（默认 7 天）；响应不变 |
| GET/PUT | `/api/v1/settings/waiver` | 新增（`setting:manage`） |

- 现有调用方（前端/测试）传 `expireAt` 时行为不变；旧测试不破。
- 审计：设置修改写 `SETTING_UPDATE`；批量部署沿用每项 `PUBLISH` 审计（不新增动作）。

## 5. 测试口径

1. 人员列：任务成员 TEST/OPS 用户出现在 `testers/deployers`（含 name）；无成员空数组；
   其他角色不出现；已归档任务仍可读。
2. 豁免默认：`expireAt=null` → 过期时间约为 now+7d（容差）；配置改 10 → now+10d；
   显式 91 天仍 40000；显式早于当前 40000；空原因 40000；审计 `SETTING_UPDATE`。
3. 设置接口：默认 7；1–90 校验；无权限 40009；读失败回退 7。
4. 批量部署（前端探针断言）：多选 2 项 → 串行两次 POST；一项失败（模拟锁占用/无效状态）不影响另一项；
   结果摘要含成功/失败数与原因；已 DONE 不可勾选。
5. 回归：单条发布、豁免既有测试、发布详情既有字段全绿。
6. ArchUnit/包规则。

## 6. 取舍

- **批量部署不做后端事务性批量**：每项独立发布/独立审计；一项失败不自动回滚已成功的项（与 FR-B-02 一致：
  「失败项不影响已成功项」）。
- **人员列只展示角色成员，不做「部署人=执行人」推断**：执行人仍是 `release_order.executed_by`。
- **豁免默认天数可配但上限 90 天不变**；配置区放在门禁规则块内，不新增页面。
- 不做 PART-01/04/05/07：资源页面列、单 DE 重部署与日志、多连接映射、兼容模式语义分别需要独立切片
  （PART-04/05 需要后端编排/引擎能力）。