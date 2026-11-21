import { api, qs } from './http'
import type {
  BindingView,
  CodingJobView,
  EnvDeView,
  ItemView,
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