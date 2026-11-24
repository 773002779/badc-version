# P4-B 通知实现计划

契约：`docs/superpowers/specs/2026-11-24-p4b-notification-design.md`（下称「设计」）。设计里没写的细节，
按现有代码风格就近取；实现若偏离设计，先改设计再改代码，或回写设计并在报告中列出偏差。

## Task 1 — 后端：落库、接收人、渠道、事件接线

范围：

1. `V26__notification.sql`：`notification`、`notification_setting` + 种子行（按设计 §2）。
2. domain：`NotificationChannel`、`NotificationStatus`、`NotificationEventKey`（含中文名，web 不维护枚举）；
   记录型对象可放 application 层，但**状态/事件枚举放 domain**，web 层不得 import domain 枚举
   （ArchUnit 既有规则：web 不依赖 domain/infra 枚举；DTO 用字符串）。
3. infra：`NotificationPO`、`NotificationSettingPO`、`NotificationMapper`（MyBatis `@Select`/`@Insert` 风格
   与 `RollbackRecordMapper` 一致）、`NotificationSettingMapper`。
4. application：`NotificationService`（`notify(cmd)` 写站内 + 视配置写企微、`REQUIRES_NEW`、吞异常）、
   `NotificationRecipientResolver`（设计 §4，含封板聚合）、`NotificationQueryService`（列表/未读/已读/全部已读）、
   `NotificationSettingService`（读/写，`setting:manage` 在 controller 上校验）。
5. infra：`WeComNotificationSender`（HTTP POST markdown，5s 超时，webhook 校验）、
   `NotificationDispatchJob`（15s，`release.notification.dispatch-enabled`，测试 profile 关闭；一次重试）。
6. web：`NotificationController`（设计 §6 五个端点 + 设置两个端点）、`NotificationDtos`；
   行结构含 `eventLabel`；分页 `page`/`size` 返回 `pageNo/pageSize`。
7. 接线（设计 §3 全部 13 个点）：`PipelineEventListener`（构建/测试部署/生产部署失败、生产完成）、
   `GateService`（检查不通过、门禁失效）、`TestEnvReadyEvent` 监听、`ApprovalService`/`ReleaseApprovalService`
   （待审批、审批结果）、`GateWaiverService`（豁免待审）、`RollbackCompletionService`（回滚完成）、
   `FreezeService` + `FreezeExpiryJob`（封板生效/解除）、`UnitLeaseRenewJob`（锁超时释放）。
   回滚实例排除规则与 P4-A 一致。

硬约束：

- 通知故障不得改变业务结果：触发点只 `try { notifications.notify(...) } catch` 兜底？不需要——
  `notify` 自己吞异常并记日志；调用点保持干净。任何 `notify` 签名不得抛受检异常。
- 不要在业务事务里发 HTTP（发送只在 job 里）。
- 新增依赖尽量为零；HTTP 用现有 `EngineHttpClient`/`HttpClient` 风格，别引入新库。
- 测试放 `server/src/test/java/com/sf/release/notification/`（**不要**放 `..web..` 包，ArchUnit 规则）；
  controller 测试用 `MockMvc` + `TestUserContext`，覆盖设计 §8 的 1–7 条。
- 全量 `mvn -o test` 必须绿；新测试计入总数并在报告里给数字。

交付：代码 + 测试 + `task-1-report.md`（改了什么、偏差、测试数字、未覆盖项）。

## Task 2 — 前端：铃铛、通知中心、设置块

范围：

1. `App.vue` 顶栏铃铛 + 未读徽标（30s 轮询 + 路由切换刷新），下拉最近 5 条 + 查看全部。
2. `/notifications` 页（设计 §7）：全部/未读筛选、分页、单条已读、全部已读、空态、点击跳对象页。
3. 设置页「消息通知」块：开关 + webhook + 保存；无权限只读给原因。
4. `web/src/api/{index,types}.ts` 增加六个接口（分页用 `page`/`size`）。
5. `scripts/verify-p4b-ui.mjs`：两主题断言铃铛/未读/通知中心/已读，0 console error；
   `docs/design/shots/notifications-{light,dark}.png` + `settings-notify-{light,dark}.png`。
6. 需要演示数据时用 `scripts/demo-notify.sql`（幂等、demo 标记），不得破坏既有 demo 单。

验收：`npm run build` 通过；探针两主题全绿。

## Task 3 — 文档收口

1. 技术方案新 §8.8 实现落点 + §12.2.5 接口；前端设计 §13；
2. 未实现清单 GAP-02 标完成（附 commit/端点/事件数）+ 残留（at-least-once、事务窗口、
   封板瞬时影响面、单租户 webhook、无保留期清理）；
3. 需求规格说明 §10 一句说明；台账 R47+ 与日志。

## 顺序与门禁

Task 1 → lead 评审（重点：接收人兜底、去重键、失败隔离、回滚实例排除）→ Task 2 → 联调
（真实触发一条站内通知，确认铃铛可见）→ Task 3。每步提交独立 commit，不 amend 旧提交。