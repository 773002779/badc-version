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
  gateStatus?: string | null
  triggerType: string
  rerunFromNode?: string | null
  fullRerun?: boolean | null
  commitSha?: string | null
  imageDigest?: string | null
  order?: { oaTicketNo?: string | null; plannedReleaseTime?: string | null } | null
  nodes: ReleaseNode[]
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