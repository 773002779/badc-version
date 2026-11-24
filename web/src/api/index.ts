import { ApiError, api, currentUser, qs } from './http'
import type {
  AdmissionView,
  AuditFilter,
  AuditPage,
  BindingView,
  CodingConnView,
  CodingJobView,
  CodingProbeView,
  DailyDetailView,
  DailyPage,
  DailyStartResult,
  EnvDeView,
  ExecuteResult,
  FreezeView,
  ItemGate,
  ItemView,
  MeView,
  NotificationPage,
  NotificationSettingView,
  PageResult,
  ReleaseDetail,
  ReleaseRecord,
  ReleaseRecordRow,
  RollbackResult,
  RollbackVersions,
  SubsystemView,
  TaskDetailView,
  TaskView,
  UnitView,
  WindowCheckView,
  WindowView
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

// -------------------------------------------------------------- daily (P5-A)

/**
 * Starts one daily release of the 变更项. It creates no 发布单, takes no unit lock and never
 * changes the item status, so the same item may be started several times in parallel.
 */
export const startDaily = (itemId: number) =>
  api<DailyStartResult>(`/api/v1/items/${itemId}/daily`, { method: 'POST' })

/** One page of the item's daily records, newest first (`page`/`size` are the server's names). */
export const listDaily = (itemId: number, page = 1, size = 10) =>
  api<DailyPage>(`/api/v1/items/${itemId}/daily${qs({ page, size })}`)

/** One daily instance with its nodes, tasks and engine log links. */
export const getDaily = (pipelineNo: string) =>
  api<DailyDetailView>(`/api/v1/daily/${pipelineNo}`)

// ------------------------------------------------------------------ releases
export const getRelease = (publishNo: string) => api<ReleaseDetail>(`/api/v1/releases/${publishNo}`)

/**
 * The global 发布记录 list; every filter except the page is optional (server-side data scope).
 * This endpoint alone names its page parameters `page`/`size` (design §4); the older lists use
 * `pageNo`/`pageSize`, so the mapping stays in this one function.
 */
export const listReleases = (
  params: {
    page?: number
    size?: number
    keyword?: string
    status?: string
    systemId?: number
    unitId?: number
  } = {}
) =>
  api<PageResult<ReleaseRecordRow>>(
    `/api/v1/releases${qs({ page: 1, size: 20, ...params })}`
  )

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

// ------------------------------------------------- production control (P3)

/** The stored admission of one 发布单: latest snapshot, its chain, the review preview. */
export const getAdmission = (publishNo: string) =>
  api<AdmissionView>(`/api/v1/releases/${publishNo}/admission`)

/** The draft judgement of the submit dialog: six steps, no write, no snapshot. */
export const checkAdmission = (
  publishNo: string,
  body: { plannedReleaseTime?: string | null; oaTicketNo?: string | null }
) =>
  api<AdmissionView>(`/api/v1/releases/${publishNo}/admission-check`, {
    method: 'POST',
    body: JSON.stringify(body)
  })

export const submitProd = (
  publishNo: string,
  body: { plannedReleaseTime?: string | null; oaTicketNo?: string | null }
) =>
  api<AdmissionView>(`/api/v1/releases/${publishNo}/submit-prod`, {
    method: 'POST',
    body: JSON.stringify(body)
  })

/** After a rejection: a new snapshot and a fresh chain; the old chain stays as history. */
export const resubmitProd = (
  publishNo: string,
  body: { plannedReleaseTime?: string | null; oaTicketNo?: string | null }
) =>
  api<AdmissionView>(`/api/v1/releases/${publishNo}/resubmit`, {
    method: 'POST',
    body: JSON.stringify(body)
  })

export const approveRelease = (publishNo: string, comment?: string) =>
  api<AdmissionView>(`/api/v1/releases/${publishNo}/approve`, {
    method: 'POST',
    body: JSON.stringify({ comment })
  })

export const rejectRelease = (publishNo: string, reason: string) =>
  api<AdmissionView>(`/api/v1/releases/${publishNo}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reason })
  })

/** Executes production, or resumes the batch-boundary pause; the answer is the real status. */
export const executeRelease = (publishNo: string) =>
  api<ExecuteResult>(`/api/v1/releases/${publishNo}/execute`, { method: 'POST' })

export const listFreezes = (
  params: { active?: boolean; subsystemId?: number; plannedAt?: string } = {}
) => api<FreezeView[]>(`/api/v1/freezes${qs({ ...params, active: params.active === undefined ? undefined : String(params.active) })}`)

export const createFreeze = (body: {
  scope: string
  subsystemId?: number | null
  reason: string
  impact?: string
  startAt?: string | null
  endAt: string
}) => api<FreezeView>('/api/v1/freezes', { method: 'POST', body: JSON.stringify(body) })

export const releaseFreeze = (id: number, reason: string) =>
  api<FreezeView>(`/api/v1/freezes/${id}/release`, {
    method: 'POST',
    body: JSON.stringify({ reason })
  })

/** The effective window of a system (subsystem override → GLOBAL default). */
export const getWindow = (subsystemId?: number) =>
  api<WindowView>(`/api/v1/windows${qs({ subsystemId })}`)

export const saveWindow = (body: {
  scope: string
  subsystemId?: number | null
  enabled: boolean
  timezone: string
  rules: Array<{ dayOfWeek: number; start: string; end: string }>
  exceptions: Array<{ date: string; mode: string; start?: string | null; end?: string | null }>
}) => api<WindowView>('/api/v1/windows', { method: 'PUT', body: JSON.stringify(body) })

export const checkWindow = (body: { subsystemId?: number; at?: string | null }) =>
  api<WindowCheckView>('/api/v1/windows/check', { method: 'POST', body: JSON.stringify(body) })

// ------------------------------------------------- rollback execute (P4-A)

/** The drawer data: current online version, up to ten targets, and the must-read notes. */
export const getRollbackVersions = (unitId: number) =>
  api<RollbackVersions>(`/api/v1/units/${unitId}/rollback-versions`)

/** Executes a production rollback immediately; a freeze only adds a `freezeWarning`. */
export const executeRollback = (body: { unitId: number; targetDigest: string; reason: string }) =>
  api<RollbackResult>('/api/v1/rollbacks', { method: 'POST', body: JSON.stringify(body) })

// --------------------------------------------------------- notifications (P4-B)

/**
 * The caller's in-app notification page, newest first; the same response carries the unread badge.
 * Like `listReleases` this endpoint names its paging `page`/`size` (design §6, R44); `unreadOnly` is
 * only sent when it is on, so the default request stays the plain list.
 */
export const listNotifications = (
  params: { page?: number; size?: number; unreadOnly?: boolean } = {}
) =>
  api<NotificationPage>(
    `/api/v1/notifications${qs({
      page: 1,
      size: 20,
      ...params,
      unreadOnly: params.unreadOnly ? 'true' : undefined
    })}`
  )

/** The badge only; the bell polls this on route change and every 30s. */
export const getUnreadNotificationCount = () =>
  api<{ unread: number }>('/api/v1/notifications/unread-count')

/** Marks one of the caller's rows read; the backend answers success on a second call too. */
export const markNotificationRead = (id: number) =>
  api<{ id: number; read: boolean }>(`/api/v1/notifications/${id}/read`, { method: 'POST' })

/** Marks every unread row of the caller read; `updated` is how many changed. */
export const markAllNotificationsRead = () =>
  api<{ updated: number }>('/api/v1/notifications/read-all', { method: 'POST' })

/** The 「消息通知」 block; requires `setting:manage`. */
export const getNotificationSetting = () =>
  api<NotificationSettingView>('/api/v1/settings/notifications')

/** Saves the block; a blank webhook clears it, and the switch cannot be on without an address. */
export const saveNotificationSetting = (body: {
  wecomEnabled: boolean
  wecomWebhook?: string | null
}) =>
  api<NotificationSettingView>('/api/v1/settings/notifications', {
    method: 'PUT',
    body: JSON.stringify(body)
  })

// ------------------------------------------------------------------ audit (P4-C)

/**
 * One page of audit rows, newest first. `page`/`size` are this endpoint's own paging names (audit
 * design §4, R44); every filter is optional and the server narrows the rows to the caller's scope.
 */
export const listAudit = (params: AuditFilter & { page?: number; size?: number } = {}) =>
  api<AuditPage>(`/api/v1/audit${qs({ page: 1, size: 10, ...params })}`)

/**
 * The same filter as a CSV download. This is deliberately not an `api()` call: the endpoint answers
 * a file body instead of the JSON envelope, and `window.open` would drop the `X-User-Id` header.
 * The caller turns the blob into a download; a rejected filter still answers the JSON envelope,
 * which surfaces here as an {@link ApiError} so the page can show `ElMessage.error`.
 */
export async function exportAudit(
  params: AuditFilter = {}
): Promise<{ blob: Blob; filename: string }> {
  let res: Response
  try {
    res = await fetch(`/api/v1/audit/export${qs({ ...params })}`, {
      headers: { 'X-User-Id': currentUser() }
    })
  } catch {
    throw new ApiError(0, '无法连接后端服务（请确认已运行 scripts\\start.ps1）')
  }

  const type = res.headers.get('Content-Type') ?? ''
  // The happy path is `text/csv`; a validation failure arrives as JSON even on a 200.
  if (!res.ok || !type.toLowerCase().includes('text/csv')) {
    let body: { code?: number; message?: string } | null = null
    try {
      body = (await res.json()) as { code?: number; message?: string }
    } catch {
      body = null
    }
    throw new ApiError(body?.code ?? res.status, body?.message || `导出失败（HTTP ${res.status}）`)
  }

  const disposition = res.headers.get('Content-Disposition') ?? ''
  const named = /filename="?([^";]+)"?/i.exec(disposition)
  return { blob: await res.blob(), filename: named?.[1] ?? `audit-${Date.now()}.csv` }
}
