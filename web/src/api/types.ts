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
  createdAt?: string
  updatedAt?: string
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