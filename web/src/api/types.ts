export interface PageResult<T> {
  total: number
  pageNo: number
  pageSize: number
  records: T[]
}

export interface SubsystemView {
  id: number
  name: string
  ownerId: string
  agentId?: string | null
  status: string
  description?: string | null
  gitUrl?: string | null
  codingProject?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface UnitView {
  id: number
  subsystemId: number
  name: string
  type: string
  du?: string | null
  ownerId: string
  frameworkTemplate?: string | null
  branchMode?: string | null
  status: string
  description?: string | null
  modulePath?: string | null
  deployType?: string | null
  imageType?: string | null
  imageName?: string | null
  /** Filter-only labels (V16): never part of a build or deploy payload. */
  module?: string | null
  tag?: string | null
  /** LIB only (P6-A/V29): the Maven coordinates of the produced artifact; null for a UNIT. */
  artifactGroupId?: string | null
  artifactArtifactId?: string | null
  createdAt?: string
  updatedAt?: string
}

/** One deployment unit that depends on a library by `modulePath == artifactId` (heuristic). */
export interface LibDependentView {
  unitId: number
  name: string
  modulePath?: string | null
}

/** The dependents response of `GET /api/v1/libs/{unitId}/dependents` (P6-A design §4). */
export interface LibDependentsView {
  libUnitId: number
  artifactId: string
  /** The only source this slice implements: `MODULE_PATH` (a documented heuristic). */
  source: string
  dependents: LibDependentView[]
}

export interface EnvDeView {
  id: number
  unitId: number
  envKind: string
  envTag?: string | null
  deName: string
  resourceName?: string | null
  codingDeployTarget?: string | null
  deployBatchNo: number
  createdAt?: string
  updatedAt?: string
}

export interface MemberView {
  userId: string
  name?: string | null
  role: string
}

export interface TaskView {
  id: number
  name: string
  subsystemId: number
  subsystemName?: string | null
  creator: string
  planFinishDate?: string | null
  purpose?: string | null
  status: string
  createdAt?: string
  done: number
  total: number
  rate: number
  hasBlocking: boolean
}

export interface TaskDetailView {
  task: TaskView
  members: MemberView[]
  itemCount: number
}

export interface ItemView {
  id: number
  crid: string
  unitId: number
  unitName?: string | null
  type: string
  version: string
  sourceBranch: string
  planReleaseDate?: string | null
  status: string
  gateStatus: string
  createdAt?: string
  updatedAt?: string
}

export interface ReleaseTask {
  taskType: string
  batchNo: number
  targetId: number
  status: string
  durationMs?: number | null
  resultSummary?: string | null
  externalUrl?: string | null
}

export interface ReleaseNode {
  nodeType: string
  seqNo: number
  status: string
  startedAt?: string | null
  finishedAt?: string | null
  tasks: ReleaseTask[]
}

export interface ReleaseDetail {
  publishNo: string
  pipelineNo: string
  status: string
  /** Why the instance is PAUSED / FAILED (the operator-facing explanation). */
  statusReason?: string | null
  /** The check dimension; `null` only for an order without an execution. */
  gateStatus?: string | null
  /** The test dimension (V19 split the two orthogonal gates). */
  testStatus?: string | null
  triggerType: string
  rerunFromNode?: string | null
  fullRerun?: boolean | null
  commitSha?: string | null
  imageDigest?: string | null
  order?: { oaTicketNo?: string | null; plannedReleaseTime?: string | null } | null
  nodes: ReleaseNode[]
  itemId?: number | null
  /** The deployment unit owning the item; the rollback drawer needs it. */
  unitId?: number | null
}

export interface ReleaseRecord {
  pipelineNo: string
  triggerType: string
  status: string
  startAt?: string | null
  endAt?: string | null
  operator?: string | null
}

export interface BindingView {
  taskKind: string
  taskId: string
  paramsJson?: string | null
  enabled?: boolean
}

/** One selectable CODING job of the system's project. */
export interface CodingJobView {
  taskId: string
  name?: string | null
  type?: string | null
}

// ------------------------------------------------------------------- gate (P2)

export type GateConclusion = 'RUNNING' | 'PASS' | 'WAIVED' | 'BLOCK' | 'INVALID'

export interface GateCheck {
  kind: string
  label: string
  stage: 'IMAGE' | 'ENV'
  status: string
  statusLabel: string
  severity: string
  severityLabel: string
  issues: number
  scannedObject?: string | null
  summary?: string | null
  reportUrl?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  deadlineAt?: string | null
  passed: boolean
  blocking: boolean
  waiverHint?: string | null
}

export interface GateCheckGroup {
  stage: 'IMAGE' | 'ENV'
  stageLabel: string
  passed: number
  total: number
  checks: GateCheck[]
}

export interface GateWaiver {
  id: number
  status: string
  statusLabel: string
  reason?: string | null
  impact?: string | null
  requestedBy: string
  requestedAt?: string | null
  approvedBy?: string | null
  approvedAt?: string | null
  decideReason?: string | null
  expireAt?: string | null
  active: boolean
}

export interface GateAdmission {
  eligible: boolean
  missing: string[]
  checkConclusion: GateConclusion
  checkSummary: string
  test: string
}

/** The verification panel of one 变更项 (the two orthogonal gates and their evidence). */
export interface ItemGate {
  itemId: number
  crid: string
  itemStatus: string
  gateStatus: string
  conclusion: GateConclusion
  conclusionLabel: string
  maxSeverity: string
  maxSeverityLabel: string
  passed: number
  total: number
  blocking: number
  waivable: boolean
  summary: string
  reasons: string[]
  testStatus: string
  test: string
  testPassBy?: string | null
  testPassAt?: string | null
  testFailReason?: string | null
  groups: GateCheckGroup[]
  waiver?: GateWaiver | null
  admission: GateAdmission
  instanceId: number
  imageDigest?: string | null
  commitSha?: string | null
}

/** The caller's identity and effective permissions (`GET /api/v1/me`). */
export interface MeView {
  userId: string
  roles: string[]
  perms: string[]
  allUnits: boolean
  unitIds: number[]
}

// ------------------------------------------------------- CODING connection config

/** The tenant-wide CODING connection; `configured:false` means "nothing saved yet". */
export interface CodingConnView {
  configured: boolean
  id?: number | null
  baseUrl?: string | null
  team?: string | null
  project?: string | null
  /** `tok-***`; the credential itself never leaves the server. */
  tokenHint?: string | null
  updatedAt?: string | null
  lastProbeAt?: string | null
  lastProbeOk?: boolean | null
  lastProbeMessage?: string | null
}

/** Result of "测试连接": the engine message plus the job counts it enumerated. */
export interface CodingProbeView {
  ok: boolean
  status: string
  message: string
  buildJobs: number
  deployJobs: number
  at?: string | null
  /** True when the tested values were the saved connection, so the result became "last verified". */
  persisted: boolean
}

// -------------------------------------------- production control (P3)

export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELED'

/** One step of the current snapshot's approval chain. */
export interface ApprovalStepView {
  stepNo: number
  role: string
  roleLabel: string
  status: ApprovalStatus | string
  statusLabel: string
  decidedBy?: string | null
  decidedAt?: string | null
  comment?: string | null
}

/** One of the six admission judgements of a fresh decision. */
export interface AdmissionStepView {
  key: string
  label: string
  passed: boolean
  reason?: string | null
  nextAction?: string | null
}

export interface AdmissionSnapshot {
  id: number
  orderId: number
  itemId: number
  submittedBy?: string | null
  plannedReleaseTime?: string | null
  inWindow: boolean
  windowId?: number | null
  frozen: boolean
  freezeIds: number[]
  maxSeverity?: string | null
  checkConclusion?: string | null
  waiverIds: number[]
  testConclusion?: string | null
  testPassBy?: string | null
  oaTicketNo?: string | null
  imageDigest?: string | null
  chain: string[]
  createdAt?: string | null
}

/** The pre-execution review (design 4.6): only the changed items are compared. */
export interface ReviewView {
  checkedAt?: string | null
  frozen: boolean
  freezeIds: number[]
  waiverInvalid: boolean
  digestMatched: boolean
  blocked: boolean
  reasons: string[]
}

/** The admission of one 发布单: the latest snapshot, its chain, the current statuses. */
export interface AdmissionView {
  publishNo: string
  orderId: number
  itemId: number
  itemStatus: string
  orderStatus: string
  snapshot?: AdmissionSnapshot | null
  chain: ApprovalStepView[]
  nextAction?: string | null
  /** Non-null only on a fresh judgement (submit / resubmit / admission-check). */
  admitted?: boolean | null
  steps: AdmissionStepView[]
  review?: ReviewView | null
}

export interface ExecuteResult {
  publishNo: string
  status: string
  node: string
  batchCount: number
  review?: ReviewView | null
  statusReason?: string | null
}

export interface FreezeView {
  id: number
  scope: string
  scopeLabel: string
  subsystemId?: number | null
  reason: string
  impact?: string | null
  initiator: string
  startAt?: string | null
  endAt?: string | null
  status: string
  statusLabel: string
  releasedBy?: string | null
  releasedAt?: string | null
  /** In force right now (not derived from `status` on the client). */
  active: boolean
  createdAt?: string | null
}

export interface WindowRuleView {
  dayOfWeek: number
  start: string
  end: string
}

export interface WindowExceptionView {
  date: string
  mode: string
  start?: string | null
  end?: string | null
}

export interface WindowView {
  configured: boolean
  id?: number | null
  scope?: string | null
  subsystemId: number
  enabled: boolean
  inherited: boolean
  timezone?: string | null
  rules: WindowRuleView[]
  exceptions: WindowExceptionView[]
  warnings: string[]
}

export interface WindowSpan {
  start: string
  end: string
}

export interface WindowCheckView {
  inWindow: boolean
  span?: WindowSpan | null
  next?: WindowSpan | null
  configured: boolean
  windowId?: number | null
  enabled: boolean
  timezone?: string | null
  scope?: string | null
  inherited: boolean
}

// ------------------------------------------------ rollback + records (P4-A)

/** One environment family the image runs in; both the machine value and its Chinese label. */
export interface RollbackEnvironment {
  kind: string
  label: string
}

/**
 * One deployable version of the rollback drawer: a successfully deployed image digest. The first
 * row of the list is the current online version; the rest are rollback targets.
 */
export interface RollbackVersion {
  digest: string
  finishedAt?: string | null
  publishNo: string
  executedBy?: string | null
  batchCount: number
  environments: RollbackEnvironment[]
}

/** The rollback drawer data: current version, up to ten history targets, and the must-read notes. */
export interface RollbackVersions {
  current: RollbackVersion
  history: RollbackVersion[]
  warnings: string[]
}

/** The answer of `POST /api/v1/rollbacks`; `freezeWarning` is null outside a freeze. */
export interface RollbackResult {
  rollbackId: number
  pipelineNo: string
  unitId: number
  targetDigest: string
  freezeWarning?: string | null
}

/**
 * One row of the global 发布记录 page. One row per release order; the newest pipeline instance is
 * joined in. `pipelineNo` is null for an order that was never executed.
 */
export interface ReleaseRecordRow {
  publishNo: string
  itemId: number
  crid: string
  version: string
  subsystemId: number
  subsystemName?: string | null
  unitId: number
  unitName?: string | null
  orderStatus: string
  orderStatusLabel: string
  itemStatus: string
  itemStatusLabel: string
  plannedReleaseTime?: string | null
  createdAt?: string | null
  executedBy?: string | null
  pipelineNo?: string | null
  pipelineStatus?: string | null
}

// --------------------------------------------------- notification centre (P4-B)

/**
 * One in-app notification row (design §6): the notification centre and the topbar bell share it.
 * `eventLabel` is the server-side Chinese name — the web keeps no copy of the event enum. `read` is
 * the server's `read_at != null`.
 */
export interface NotificationView {
  id: number
  eventKey: string
  eventLabel: string
  objectType: string
  objectId: string
  objectLabel?: string | null
  title: string
  body: string
  status: string
  read: boolean
  createdAt?: string | null
  sentAt?: string | null
  failureReason?: string | null
}

/** One page of the caller's notifications; `unread` rides along so the badge refreshes with it. */
export interface NotificationPage extends PageResult<NotificationView> {
  unread: number
}

/** The 「消息通知」 settings block; the webhook is returned as-is to a `setting:manage` caller. */
export interface NotificationSettingView {
  wecomEnabled: boolean
  wecomWebhook?: string | null
  updatedBy?: string | null
  updatedAt?: string | null
}

// --------------------------------------------------------- platform settings (P6-B)

/**
 * The 「门禁规则」 block: the enabled check types by their machine names (design §3). The web keeps
 * no enum of its own; `enabledChecks` is authoritative and unknown names are preserved.
 */
export interface GateSettingView {
  enabledChecks: string[]
  updatedBy?: string | null
  updatedAt?: string | null
}

/**
 * The 「审批人名单」 block: one user id list per approval role. These are the *effective* lists: a
 * stored empty list falls back to the role's current `user_role` members (design §1), so the page
 * shows the roles that can actually approve right now.
 */
export interface ApproverSettingView {
  opsLead: string[]
  deptManager: string[]
  updatedBy?: string | null
  updatedAt?: string | null
}

/** The 「锁超时」 block: the abandoned-pause bound in hours; the lease TTL itself stays 4h. */
export interface LockSettingView {
  pauseBoundHours: number
  updatedBy?: string | null
  updatedAt?: string | null
}

// ---------------------------------------------------------------- audit (P4-C)

/**
 * One audit row (audit design §4). `actionLabel` is the server's Chinese action name, so the web
 * keeps no copy of the action enum. `before`/`after` are the stored JSON texts of the changed fields
 * only and may be null (a new object has no before, a delete no after).
 */
export interface AuditRow {
  id: number
  action: string
  actionLabel: string
  actor: string
  objectType: string
  objectId: string
  objectLabel?: string | null
  summary: string
  reason?: string | null
  before?: string | null
  after?: string | null
  traceId?: string | null
  createdAt?: string | null
}

/** The audit filters, shared by the page and the CSV export; the export ignores the page fields. */
export interface AuditFilter {
  objectType?: string
  objectId?: string
  action?: string
  actor?: string
  /** ISO-8601 Instant; the server answers 40000 when `from` is after `to`. */
  from?: string
  to?: string
  /** The unit-detail convenience filter: only that unit's scoped rows. */
  unitId?: number
}

/** One page of audit rows; `pageNo`/`pageSize` echo the server's own paging (R44). */
export interface AuditPage extends PageResult<AuditRow> {}

// --------------------------------------------------------------- daily (P5-A)

/**
 * One row of the 日常发布 records of a 变更项 (design §6). `branch` is the item's read-only
 * source branch; `commitSha` / `imageDigest` stay null until the daily build reports them.
 */
export interface DailyRecordView {
  pipelineNo: string
  status: string
  requestedBy?: string | null
  branch?: string | null
  commitSha?: string | null
  createdAt?: string | null
  startAt?: string | null
  endAt?: string | null
  imageDigest?: string | null
}

/** The daily records list: `page`/`size` are this endpoint's own paging names. */
export interface DailyPage extends PageResult<DailyRecordView> {}

/** One task of a daily node; `externalUrl` is the engine log link (null until triggered). */
export interface DailyTaskView {
  taskType: string
  status: string
  externalUrl?: string | null
  summary?: string | null
}

/** One node of the daily pipeline (`BUILD` seq 0, `DAILY_DEPLOY` seq 1). */
export interface DailyNodeView {
  nodeType: string
  status: string
  tasks: DailyTaskView[]
}

/** The full daily instance: the row fields plus its nodes and tasks. */
export interface DailyDetailView extends DailyRecordView {
  nodes: DailyNodeView[]
}

/** The answer of `POST /api/v1/items/{itemId}/daily`. */
export interface DailyStartResult {
  pipelineNo: string
  instanceId: number
}

// ------------------------------------------------- stop / exit / close (P5-B)

/**
 * The answer of stop/exit/close (design §4): the object's latest status, the item's status and the
 * pipeline number of the instance this call terminated ({@code null} when none was active).
 * {@code status} is null for an item whose close had no 发布单 to mirror on.
 */
export interface ControlResult {
  status?: string | null
  itemStatus: string
  terminatedPipelineNo?: string | null
}

/** The answer of archive: the task status and the cascade counts the page reports. */
export interface ArchiveResult {
  taskStatus: string
  closedItems: number
  terminatedInstances: number
}

// -------------------------------------------------------------- todos (P5-C)

/**
 * One row of the 待办中心 (todo design §1/§3). Every field is a server string, including the ids and
 * `waitingHours`: the page only renders and navigates, and the Chinese status label comes from the
 * server's domain enums, so the web keeps no copy of them. `link` is the ready-made relative path
 * (`/tasks/{id}?item={itemId}`, `/releases/{publishNo}` or `/tasks/{id}`) the web must not rebuild.
 */
export interface TodoItemView {
  objectType: string
  objectId: string
  label: string
  status: string
  statusLabel: string
  waitingSince?: string | null
  /** Whole hours elapsed on the server clock, as a string; `"0"` means "刚刚". */
  waitingHours: string
  unitId?: string | null
  link?: string | null
}

/**
 * One of the four queues: `count` is the full count, `items` at most the requested `limit` rows in
 * oldest-waiting-first order, so the page can say "共 N 项，显示前 M 项".
 */
export interface TodoGroupView {
  count: number
  items: TodoItemView[]
}

/** The four groups of `GET /api/v1/todos`; all four are present even when empty. */
export interface TodoBoardView {
  test: TodoGroupView
  approval: TodoGroupView
  execute: TodoGroupView
  mine: TodoGroupView
}
