import { api, qs } from './http'
import type {
  BindingView,
  CodingConnView,
  CodingJobView,
  CodingProbeView,
  EnvDeView,
  ItemGate,
  ItemView,
  MeView,
  PageResult,
  ReleaseDetail,
  ReleaseRecord,
  SubsystemView,
  TaskDetailView,
  TaskView,
  UnitView
} from './types'

// ---------------------------------------------------------------- subsystems
export const listSubsystems = (pageNo = 1, pageSize = 50, keyword?: string) =>
  api<PageResult<SubsystemView>>(`/subsystems${qs({ pageNo, pageSize, keyword })}`)

export const getSubsystem = (id: number) => api<SubsystemView>(`/subsystems/${id}`)

export const createSubsystem = (body: {
  name: string
  ownerId: string
  agentId?: string
  description?: string
  gitUrl?: string
  codingProject?: string
}) => api<number>('/subsystems', { method: 'POST', body: JSON.stringify(body) })

/** Whole-form replace (as the backend defines it): the subsystem's system id cannot change. */
export const updateSubsystem = (
  id: number,
  body: {
    name: string
    ownerId: string
    agentId?: string
    status?: string
    description?: string
    gitUrl?: string
    codingProject?: string
  }
) => api<void>(`/subsystems/${id}`, { method: 'PUT', body: JSON.stringify(body) })

// --------------------------------------------------------------------- units
export const listUnits = (
  params: {
    pageNo?: number
    pageSize?: number
    keyword?: string
    subsystemId?: number
    type?: string
    module?: string
    tag?: string
  } = {}
) => api<PageResult<UnitView>>(`/units${qs({ pageNo: 1, pageSize: 100, ...params })}`)

export const getUnit = (id: number) => api<UnitView>(`/units/${id}`)

export const updateUnit = (
  id: number,
  body: {
    name: string
    ownerId: string
    type?: string
    du?: string
    frameworkTemplate?: string
    branchMode?: string
    status?: string
    description?: string
    modulePath?: string
    deployType?: string
    imageType?: string
    imageName?: string
    module?: string
    tag?: string
  }
) => api<void>(`/units/${id}`, { method: 'PUT', body: JSON.stringify(body) })

export const createUnit = (body: {
  subsystemId: number
  name: string
  ownerId: string
  type?: string
  du?: string
  frameworkTemplate?: string
  branchMode?: string
  modulePath?: string
  deployType?: string
  imageType?: string
  imageName?: string
}) => api<number>('/units', { method: 'POST', body: JSON.stringify(body) })

export const listCodingTasks = (unitId: number) =>
  api<BindingView[]>(`/units/${unitId}/coding-tasks`)

/** Candidate jobs of the system's CODING project (BUILD or DEPLOY namespace). */
export const listCodingJobs = (unitId: number, taskKind: string) =>
  api<CodingJobView[]>(`/units/${unitId}/coding-jobs${qs({ taskKind })}`)

export const bindCodingTasks = (
  unitId: number,
  bindings: Array<{ taskKind: string; taskId: string; paramsJson?: string | null }>
) =>
  api<void>(`/units/${unitId}/coding-tasks`, {
    method: 'PUT',
    body: JSON.stringify({ bindings })
  })

export const listEnvDes = (unitId: number) => api<EnvDeView[]>(`/units/${unitId}/env-des`)

export const addEnvDe = (
  unitId: number,
  body: {
    envKind: string
    deName: string
    envTag?: string
    resourceName?: string
    codingDeployTarget?: string
    deployBatchNo?: number
  }
) => api<number>(`/units/${unitId}/env-des`, { method: 'POST', body: JSON.stringify(body) })

// --------------------------------------------------------------------- tasks
export const listTasks = (
  params: { pageNo?: number; pageSize?: number; subsystemId?: number; status?: string; keyword?: string } = {}
) => api<PageResult<TaskView>>(`/tasks${qs({ pageNo: 1, pageSize: 20, ...params })}`)

export const getTask = (id: number) => api<TaskDetailView>(`/tasks/${id}`)

export const listTaskItems = (id: number) => api<ItemView[]>(`/tasks/${id}/items`)

export const createTask = (body: {
  name: string
  subsystemId: number
  planFinishDate?: string
  purpose?: string
}) => api<number>('/tasks', { method: 'POST', body: JSON.stringify(body) })

export const addTaskMembers = (
  id: number,
  members: Array<{ userId: string; role: string }>
) => api<void>(`/tasks/${id}/members`, { method: 'POST', body: JSON.stringify({ members }) })

// --------------------------------------------------------------------- items
export const createItem = (body: {
  taskId: number
  unitId: number
  version: string
  planReleaseDate: string
}) => api<{ itemId: number; crid: string }>('/api/v1/items', { method: 'POST', body: JSON.stringify(body) })

export const publishItem = (itemId: number) =>
  api<{ publishNo: string; pipelineNo: string }>(`/api/v1/items/${itemId}/publish`, { method: 'POST' })

// ------------------------------------------------------------------ releases
export const getRelease = (publishNo: string) => api<ReleaseDetail>(`/api/v1/releases/${publishNo}`)

export const getReleaseRecords = (publishNo: string) =>
  api<ReleaseRecord[]>(`/api/v1/releases/${publishNo}/records`)

/** Resumes a paused execution; the backend rejects it when the gate is not satisfied. */
export const resumeRelease = (publishNo: string) =>
  api<{ publishNo: string; pipelineNo: string; status: string; resumeNode: string }>(
    `/api/v1/releases/${publishNo}/resume`,
    { method: 'POST' }
  )

// ------------------------------------------------------------------- gate (P2)
export const getMe = () => api<MeView>('/api/v1/me')

/** The verification panel: both gates, every check, the waiver and the admission. */
export const getItemGate = (itemId: number) => api<ItemGate>(`/api/v1/items/${itemId}/gate`)

export const passItemTest = (itemId: number) =>
  api<ItemGate>(`/api/v1/items/${itemId}/gate/test-pass`, { method: 'POST' })

export const failItemTest = (itemId: number, reason: string) =>
  api<ItemGate>(`/api/v1/items/${itemId}/gate/test-fail`, {
    method: 'POST',
    body: JSON.stringify({ reason })
  })

export const revokeItemTest = (itemId: number, reason: string) =>
  api<ItemGate>(`/api/v1/items/${itemId}/gate/test-revoke`, {
    method: 'POST',
    body: JSON.stringify({ reason })
  })

export const refreshGateCheck = (itemId: number, kind: string, instanceId = 0) =>
  api<ItemGate>(`/api/v1/items/${itemId}/gate/checks/${kind}/refresh${qs({ instanceId })}`, {
    method: 'POST'
  })

export const requestWaiver = (
  itemId: number,
  body: { reason: string; impact?: string; expireAt: string }
) => api<ItemGate>(`/api/v1/items/${itemId}/gate/waiver`, { method: 'POST', body: JSON.stringify(body) })

export const approveWaiver = (itemId: number, waiverId: number, note?: string) =>
  api<ItemGate>(`/api/v1/items/${itemId}/gate/waiver/${waiverId}/approve`, {
    method: 'POST',
    body: JSON.stringify({ note })
  })

export const rejectWaiver = (itemId: number, waiverId: number, note: string) =>
  api<ItemGate>(`/api/v1/items/${itemId}/gate/waiver/${waiverId}/reject`, {
    method: 'POST',
    body: JSON.stringify({ note })
  })

// ---------------------------------------------------- CODING connection (settings)

/** The tenant-wide connection; `configured:false` when nothing has been saved yet. */
export const getCodingConn = () => api<CodingConnView>('/api/v1/coding/conn')

/** Saves the connection; a blank token keeps the stored credential. */
export const saveCodingConn = (body: {
  baseUrl: string
  team: string
  project: string
  token?: string
}) => api<CodingConnView>('/api/v1/coding/conn', { method: 'PUT', body: JSON.stringify(body) })

/** Tests a draft (unsaved) or the stored connection; never persists anything. */
export const testCodingConn = (
  body: { baseUrl?: string; team?: string; project?: string; token?: string } = {}
) =>
  api<CodingProbeView>('/api/v1/coding/conn/test', {
    method: 'POST',
    body: JSON.stringify(body)
  })