# P4-B 通知（站内 + 企微）设计

> 阶段：P4-B（GAP-02）。前置：P1–P3、P4-A 已交付。需求依据：FR-N-01、C22、J7（12h 提醒/24h 升级属 P7，不在本阶段）。
> 本文件是实现契约：表结构、事件清单、接收人规则、发送语义、接口与测试口径以这里为准；实现偏离必须回写本节。

## 1. 范围与非目标

**做**

1. `notification` 落库：一条「事件 × 接收人 × 渠道」一行，站内可读、已读可追踪、去重。
2. 事件覆盖（GAP-02 验收 2 的 12 类）：构建失败、测试部署失败、生产部署失败、检查不通过、
   测试部署就绪、待审批、审批结果、门禁失效（生产中）、生产完成、豁免待审、回滚完成、
   封板生效/解除、锁超时释放。
3. 接收人解析：任务成员按角色（产品/研发/测试/运维）+ 任务创建人；审批按待签步骤角色；
   封板按「受影响发布单」的任务成员，四类角色缺哪类就用系统负责人兜底（C22）。
4. 渠道：站内（必发）+ 企微机器人（设置页可配、可关；失败重试一次、记录原因，**不阻塞主流程**）。
5. 站内接口 + 未读数 + 已读；设置页「消息通知」块。
6. 页面：顶栏铃铛（未读徽标 + 最近 5 条）+ `/notifications` 通知中心。

**不做（写在设计里避免反复）**

- 邮件 / 飞书：只留 `NotificationChannel` 枚举与 `NotificationSender` 接口，不接实现（Q5 未定）。
- 通知模板管理、按用户订阅偏好、@人、群机器人多租户：二期。
- 审批 12h/24h 时效提醒、升级链：P7（GAP-12）。
- 通知失败重投队列：一次重试后 `FAILED`，不自动再投；运维可在通知中心看到失败行。

## 2. 数据模型（V26）

```sql
CREATE TABLE notification (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_key      VARCHAR(64)  NOT NULL COMMENT 'BUILD_FAILED ... FREEZE_APPLIED',
  object_type    VARCHAR(32)  NOT NULL COMMENT 'ORDER / ITEM / UNIT / FREEZE / WAIVER / INSTANCE',
  object_id      VARCHAR(64)  NOT NULL COMMENT '发布单号 / 变更项 id / 单元 id / 封板 id ...',
  object_label   VARCHAR(200) NULL,
  recipient      VARCHAR(32)  NOT NULL,
  channel        VARCHAR(16)  NOT NULL COMMENT 'INAPP / WECOM',
  title          VARCHAR(200) NOT NULL,
  body           VARCHAR(2000) NOT NULL,
  status         VARCHAR(16)  NOT NULL COMMENT 'PENDING / SENT / FAILED',
  read_at        DATETIME(3)  NULL,
  attempts       INT          NOT NULL DEFAULT 0,
  failure_reason VARCHAR(512) NULL,
  dedupe_key     VARCHAR(200) NOT NULL,
  created_at     DATETIME(3)  NOT NULL,
  sent_at        DATETIME(3)  NULL,
  UNIQUE KEY uk_notification_dedupe (dedupe_key),
  KEY idx_notification_recipient (recipient, created_at),
  KEY idx_notification_pending (status, channel, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification_setting (
  id            TINYINT PRIMARY KEY,
  wecom_enabled TINYINT      NOT NULL DEFAULT 0,
  wecom_webhook VARCHAR(512) NULL,
  updated_by    VARCHAR(32)  NULL,
  updated_at    DATETIME(3)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO notification_setting (id, wecom_enabled, wecom_webhook, updated_at)
VALUES (1, 0, NULL, NOW(3));
```

约定：

- `status`：站内行创建即 `SENT`（已送达站内），`read_at` 为空表示未读；企微行创建为 `PENDING`。
- `dedupe_key = event_key|object_type|object_id|recipient|channel|<ref>`，`<ref>` 由触发点给（实例 id、
  批次号、审批步骤号、封板 id 等），保证「同一事件同一对象同一步骤」只发一次；重复插入忽略。
- 站内行与企微行同 key 不同 channel，互不影响。
- `body` 为纯文本（站内直接展示）；企微用同一标题/正文拼 markdown，不另存。

## 3. 事件清单与触发点

`event_key` 与中文名（页面/企微都用同一套）：

| event_key | 名称 | 触发点 | 对象 | 接收人 |
|---|---|---|---|---|
| `BUILD_FAILED` | 构建失败 | `PipelineEventListener.onNodeCompleted`（BUILD/FAILED） | ORDER | 任务成员 DEV、TEST、创建人 |
| `TEST_DEPLOY_FAILED` | 测试部署失败 | 同上（TEST_DEPLOY/FAILED） | ORDER | 任务成员 DEV、TEST、创建人 |
| `PROD_DEPLOY_FAILED` | 生产部署失败 | 同上（PROD_DEPLOY/FAILED） | ORDER | 任务成员 OPS、创建人 |
| `CHECK_FAILED` | 检查不通过 | `GateService` 检查结果落 `GATE_FAIL` 处 | ORDER | 任务成员 DEV、TEST、OPS、创建人 |
| `TEST_ENV_READY` | 测试部署已就绪 | 已有 `TestEnvReadyEvent` 监听 | ORDER | 任务成员 TEST、DEV、创建人 |
| `APPROVAL_PENDING` | 待审批 | `ApprovalService`/`ReleaseApprovalService` 生成或补签出 PENDING 步骤后 | ORDER | 当前待签步骤角色的用户 |
| `APPROVAL_DECIDED` | 审批结果 | `ReleaseApprovalService.approve/reject`、`ApprovalService` 驳回/通过后 | ORDER | 创建人、任务成员（去重） |
| `GATE_INVALIDATED` | 门禁失效（生产中） | 生产中被置为门禁失效的服务方法（`GateReconcileJob`/`GateService`） | ORDER | 任务成员 OPS、创建人 |
| `PROD_DONE` | 生产完成 | `InstanceFinishedEvent`（非回滚、状态 DONE） | ORDER | 任务成员 OPS、DEV、创建人 |
| `WAIVER_PENDING` | 豁免待审批 | `GateWaiverService.create` | WAIVER | 拥有 `control:approve` 的用户 + 系统负责人 |
| `ROLLBACK_DONE` | 回滚完成 | `RollbackCompletionService` 收敛后（SUCCESS/FAILED 各一条，标题区分） | UNIT | 任务成员 OPS、DEV、回滚发起人、单元 owner |
| `FREEZE_APPLIED` | 封板生效 | `FreezeService.create` 提交后 | FREEZE | 受影响发布单的四类角色成员，见 §4.3 |
| `FREEZE_RELEASED` | 封板解除/到期 | `FreezeService.release`、`FreezeExpiryJob` | FREEZE | 同上（用封板创建时的影响面） |
| `LOCK_TIMEOUT_RELEASED` | 锁超时释放 | `UnitLeaseRenewJob` 判定暂停超上限、允许租期过期处 | UNIT | 单元 owner + 该单元在途单的创建人；兜底系统负责人 |

实现约束：

- 触发点只调用 `NotificationService.notify(cmd)`，不直接拼 SQL、不直接发 HTTP。
- 事件触发在业务事务内，`notify` 用 `REQUIRES_NEW` 写库并吞掉异常（记录 error 日志），
  **任何通知故障都不得改变业务结果**；重复调用由 `dedupe_key` 兜底。
- 站内行必须能在没有企微配置时独立工作（默认态）。
- 回滚实例（`TriggerType.ROLLBACK`）不产生 `BUILD_FAILED`/`PROD_DONE` 等前向事件；
  它只有 `ROLLBACK_DONE`。与 P4-A `PipelineEventListener` 的排除规则一致。

## 4. 接收人解析

`RecipientResolver`（application 层，依赖 mapper 读成员/角色/负责人；不依赖 web）。

### 4.1 任务成员

`release_task_member` 按 `task_id` 取成员，映射：

- 研发 = `DEV`；测试 = `TEST`；运维 = `OPS`；产品 = `PRODUCT`；`OWNER` 视同研发（提交/owner 角色）。
- 事件只选它关心的角色（见 §3），并总是并入任务创建人（`release_task.creator`）。
- 排序稳定（user_id 升序）便于断言。

### 4.2 审批人

- `APPROVAL_PENDING`：读该发布单最新快照的 `PENDING` 步骤（`approval` 表），把步骤角色
  （`OPS_LEAD`/`DEPT_MANAGER`）映射到 `user_role` 中拥有该角色的用户；多个 PENDING 步骤取第一个
  未签步骤（会签顺序），避免一次事件给两级都发。
- 兜底：该角色无任何用户时，发给系统负责人，并在正文注明「（角色无人，已转系统负责人）」。
- `WAIVER_PENDING`：`role_permission` 中拥有 `control:approve` 的用户（ADMIN/OPS_LEAD/DEPT_MANAGER）。

### 4.3 封板影响面（C22）

封板生效/解除时：

1. 取受影响的发布单：范围内、状态属于 `READY`/`PENDING_APPROVAL`/`SUBMITTED`/`EXECUTING`/
   `PROD_DEPLOYING`/`GATE_PENDING` 的单（即封板会影响或正在影响的单）；
2. 每单按 §4.1 取 `PRODUCT`/`DEV`/`TEST`/`OPS` 四类成员；
3. 某类角色在该单缺失 → 记一次系统负责人兜底（同一系统只发一条，正文含系统名）；
4. 按接收人聚合：一个人只收一条，正文列出他相关的发布单号与当前环节（最多 10 条，超出写「等 N 单」）；
   `dedupe_key` 用 `FREEZE_APPLIED|<freezeId>|<recipient>`，解除同理。

### 4.4 锁超时

接收人 = 单元 `owner_id` + 该单元在途实例所属发布单的创建人；空则系统负责人。

## 5. 渠道与发送

- 站内：`notify` 时直接插入 `channel=INAPP`、`status=SENT` 的行，`read_at=NULL`。
- 企微：
  - 设置 `wecom_enabled=0` 或 webhook 为空 → 不建企微行（验收「开关关闭时不发」）；
  - 开启时插 `channel=WECOM`、`status=PENDING` 行；
  - `NotificationDispatchJob`（`@Scheduled(fixedDelay=15_000)`，`release.notification.dispatch-enabled`
    开关，测试 profile 关闭）取 `status=PENDING AND channel=WECOM ORDER BY id LIMIT 50`，
    用 `NotificationSender` 发送；
  - 发送成功 → `SENT` + `sent_at`；失败 → 立即重试一次，仍失败 → `FAILED` + `failure_reason`
    （截断 512）+ `attempts=2`；HTTP 超时 5s；任何异常只落到该行，不影响其他行与业务；
  - 同一行由 `attempts` 递增 + `status` 收口，单实例部署下不需要分布式锁；多实例部署时允许重复
    发送一次（at-least-once），在本阶段可接受并记录。
- 企微消息体：markdown，`### {title}` + 正文 + `> {object_label} · {createdAt}`。
- webhook 地址视为敏感配置：读取接口原样返回（与 CODING 连接现有做法一致，页面可编辑）；
  发送日志不得打印完整 URL（只打 host）。

## 6. 接口

统一响应包装沿用 `ApiResponse`；异常走 `BizError`。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/notifications?page=&size=&unreadOnly=` | 登录即可 | 仅本人站内行，`created_at DESC, id DESC`；返回 `{total,pageNo,pageSize,unread,records}` |
| GET | `/api/v1/notifications/unread-count` | 登录即可 | `{unread}` |
| POST | `/api/v1/notifications/{id}/read` | 登录即可 | 只能标记本人行；已读再标返回成功（幂等） |
| POST | `/api/v1/notifications/read-all` | 登录即可 | 返回本次标记条数 |
| GET | `/api/v1/settings/notifications` | `setting:manage` | `{wecomEnabled, wecomWebhook, updatedBy, updatedAt}` |
| PUT | `/api/v1/settings/notifications` | `setting:manage` | 同上字段；webhook 需 `https://` 开头（内网 http 允许由配置放开，沿用引擎的 `allow-insecure-http` 思路，本阶段只校验非空 + 长度） |

行结构：`id, eventKey, eventLabel, objectType, objectId, objectLabel, title, body, status, read,
createdAt, sentAt, failureReason`。`eventLabel` 由服务端给中文名，web 不维护枚举。

分页参数用 `page`/`size`（R44 口径），响应 `pageNo/pageSize`。

## 7. 前端

- 顶栏：铃铛 + 未读徽标（`GET unread-count`，进入页面/路由切换时刷新，30s 轮询；`0` 不显示）；
  点开下拉显示最近 5 条 + 「查看全部」→ `/notifications`；点单条 → 标已读 + 跳到对象页
  （发布单 → `/releases/{publishNo}`；单元 → `/units/{id}`；封板 → `/control`；豁免 → `/releases/{publishNo}#gate`）。
- `/notifications`：筛选「全部 / 未读」、分页、单条标记已读、「全部已读」、空态。
- 设置页新增「消息通知」块（放在 CODING 对接之后）：企微开关、webhook、保存；无 `setting:manage`
  时只读并给原因。
- 设计基调用现有 `DataSection` / `PageHeader` / `EmptyState`，不引入新组件库；亮/暗双主题。
- 验收：`npm run build` + `scripts/verify-p4b-ui.mjs`（铃铛→通知中心→已读）0 console error，
  两张截图 `docs/design/shots/notifications-{light,dark}.png`、`settings-notify-{light,dark}.png`。

## 8. 测试口径

后端（真实测试库，沿用现有 `@SpringBootTest` 风格）：

1. 解析：四类角色齐全；缺 PRODUCT 时回落系统负责人；审批角色无人时回落；
2. 去重：同一事件重复 `notify` 只落一行；不同 recipient/channel 各自一行；
3. 渠道开关：关闭时无 WECOM 行、站内行仍在；未配置 webhook 同；
4. 发送：sender 失败一次后成功 → `SENT/attempts=2`；两次失败 → `FAILED` + 原因；
   sender 抛异常不影响 `notify` 返回、不影响其他 PENDING 行；
5. 业务隔离：`notify` 内部异常（如 dedupe 冲突、DB 异常）不改变调用方结果；
6. 接口：只读本人；越权标他人行 403/404（取 404 防枚举）；未读数；read-all 幂等；
   设置需 `setting:manage`（无权限 403）；
7. 事件覆盖：至少 6 个代表事件（BUILD_FAILED、APPROVAL_PENDING、FREEZE_APPLIED、PROD_DONE、
   ROLLBACK_DONE、LOCK_TIMEOUT_RELEASED）各一条集成断言，证明触发点真的调用了 notify。

文档：技术方案新 §8.8 + §12.2.5；前端设计 §13；未实现清单 GAP-02 标完成与残留；
需求规格 §10 说明；台账 R47+。

## 9. 取舍与风险

- **at-least-once**：企微可能重复发送（多实例、重试后进程崩溃），不追求 exactly-once。
- **事务窗口**：`REQUIRES_NEW` 写通知与业务事务不在一个原子范围，极端情况下业务回滚后仍有
  一条通知；本阶段接受，P7 若要严格一致需 outbox + 提交后投递。
- **封板瞬时影响面**：按通知时刻的在途单计算，不在封板期内滚动补发；封板后新提交的单由
  准入面板提示，不再单独通知。
- **webhook 单租户**：一个平台一个机器人地址；按系统分机器人是二期。
- **通知保留期**：本阶段不清理，P7 与审计一起做保留策略。