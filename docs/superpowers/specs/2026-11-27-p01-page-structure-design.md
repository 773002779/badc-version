# PART-01 页面信息结构补齐设计

> 需求出处：未实现清单 §2 PART-01——单元列表缺「最近发布版本、时间、占用锁」列；系统详情缺「变更明细/发布记录/
> 管控配置入口」；单元详情缺「消息通知」（操作日志已随 P4-C、流水线配置已随 P6-C 交付）。

## 1. 范围裁决（安全子集）

1. **新端点 `GET /api/v1/units/{unitId}/release-summary`**（登录 + 单元数据范围）：
   - `latest`：该单元最近一次**成功生产部署**（复用 `RollbackRecordMapper.selectCurrent`：digest、完成时间、
     发布单号、执行人）——「最近发布版本/时间」列的数据源；`null` 表示从未成功部署生产。
   - `lock`：`deploy_unit_lock` 行（instanceId、acquiredAt、expireAt），无行则 `null`——「占用锁」列的数据源。
   - 无新表无迁移；一行审计不写（只读端点，与回滚版本清单同口径）。
2. **单元列表**：新增三列「最近发布（版本/发布单/时间）」「占用锁」；数据由前端对**当前页**的单元并发调
   summary（best-effort，失败显示 `—`），不做后端批量聚合端点（分页 20 × 1 次小读，列表页可接受）。
3. **系统详情**：
   - 「发布记录」块：`listReleases({systemId, size: 10})`（复用全局记录页的行视图：发布单/变更项 CRID、版本、
     单元、状态、创建时间）——「变更明细」由发布记录行（含变更项信息）承接，**不做**按系统列变更项的新端点；
   - 「管控配置」入口：页头按钮 `router-link` 到 `/control?subsystemId={id}`；`ControlView` 读取
     `route.query.subsystemId` 并预选该系统（冻结/窗口表单的系统下拉）。
4. **单元详情「消息通知」块：降级不做**（已接受残留）：通知是发布单维度（`objectType=ORDER`），按单元聚合需要
   新的 join 查询与参数；一期通知中心（`/notifications`）+ 顶栏铃铛已可查全部通知。此裁决写入未实现清单。

## 2. 响应形状

```json
{"unitId":2,
 "latest":{"digest":"sha256:4a5b…","publishNo":"PO-…","finishedAt":"…","executedBy":"dev"},
 "lock":{"instanceId":90001,"acquiredAt":"…","expireAt":"…"}}
```

## 3. 测试口径

1. 后端 `server/src/test/java/com/sf/release/part01/UnitReleaseSummaryTest`：真实库造数（成功生产部署 →
   latest 非空且为最近一条；锁行 → lock 非空；无锁无部署 → 两 null）；未知单元 40000；范围外 40009。
2. 前端 `npm run build` 绿；探针 `scripts/verify-p1b-ui.mjs`（复用 `demo-redeploy.sql`：unit 2 有成功批 1 +
   失败批 2）双主题断言：单元列表三列渲染（最近发布=种子批 1 的发布单/时间；占用锁 `—`，锁行种子后另行断言）、
   系统详情「发布记录」块出现且含该发布单、管控入口跳转 `/control?subsystemId=2` 且 ControlView 预选系统；
   0 console error；截图 `unit-summary-{light,dark}.png`、`subsystem-records-{light,dark}.png`。

## 4. 文档收口

未实现清单 PART-01 标完成（含「消息通知降级不做」「变更明细由发布记录承接」两条已接受残留）；
技术方案 §8.21 + §12.2.17；前端设计 §26；台账 R139+。
