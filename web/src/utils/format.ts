const LABELS: Record<string, string> = {
  // item
  PENDING: '待发布',
  BUILDING: '构建中',
  TESTING: '测试中',
  GATE_PENDING: '待生产准入',
  SUBMITTED: '已提交',
  PENDING_APPROVAL: '待审批',
  READY: '待执行',
  PROD_DEPLOYING: '生产部署中',
  DONE: '已完成',
  GATE_FAILED: '门禁未通过',
  FAILED: '失败',
  REJECTED: '已驳回',
  CLOSED: '已关闭',
  TERMINATED: '已终止',
  ROLLED_BACK: '已回滚',
  // instance / node / task
  RUNNING: '进行中',
  PAUSED: '已挂起',
  SUCCESS: '成功',
  TIMEOUT: '超时',
  SKIPPED: '已跳过',
  // lifecycle of a unit / subsystem
  ACTIVE: '启用',
  INACTIVE: '停用',
  // gate
  TEST_PENDING: '待测试',
  TEST_PASS: '测试通过',
  TEST_FAIL: '测试不通过',
  TEST_REVOKED: '已撤回',
  CHECK_RUNNING: '检查中',
  CHECK_PASS: '检查通过',
  CHECK_WAIVED: '已豁免',
  CHECK_FAIL: '检查不通过',
  CHECK_INVALID: '已失效',
  // engine kinds / node types
  INITIAL: '首次发布',
  RERUN_FROM_NODE: '重跑（失败节点）',
  RERUN_FULL: '重跑（全量）',
  BUILD: '构建',
  TEST_DEPLOY: '测试部署',
  VERIFY: '验证确认',
  PROD_DEPLOY: '生产部署'
}

export type StatusDomain = 'item' | 'node' | 'task'

export function statusLabel(status?: string | null, domain: StatusDomain = 'item'): string {
  if (!status) return '—'
  if (status === 'PENDING' && domain !== 'item') return '待开始'
  return LABELS[status] ?? status
}

export type Tone = 'neutral' | 'accent' | 'success' | 'danger' | 'warn'

const TONES: Record<string, Tone> = {
  ACTIVE: 'success',
  INACTIVE: 'neutral',
  PENDING: 'neutral',
  CLOSED: 'neutral',
  TERMINATED: 'neutral',
  ROLLED_BACK: 'neutral',
  SKIPPED: 'neutral',
  BUILDING: 'accent',
  TESTING: 'accent',
  GATE_PENDING: 'accent',
  SUBMITTED: 'accent',
  READY: 'accent',
  PROD_DEPLOYING: 'accent',
  RUNNING: 'accent',
  SUCCESS: 'success',
  DONE: 'success',
  FAILED: 'danger',
  GATE_FAILED: 'danger',
  REJECTED: 'danger',
  TIMEOUT: 'danger',
  PAUSED: 'warn',
  PENDING_APPROVAL: 'warn'
}

export function statusTone(status?: string | null): Tone {
  if (!status) return 'neutral'
  return TONES[status] ?? 'neutral'
}

export function isBusyStatus(status?: string | null): boolean {
  return status === 'RUNNING' || status === 'BUILDING' || status === 'TESTING' ||
    status === 'PROD_DEPLOYING'
}

export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formatDuration(ms?: number | null): string {
  if (ms === null || ms === undefined) return '—'
  if (ms < 1000) return `${ms}ms`
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const rest = s % 60
  if (m < 60) return rest ? `${m}m${rest}s` : `${m}m`
  const h = Math.floor(m / 60)
  return `${h}h${m % 60}m`
}

export function shortSha(sha?: string | null): string {
  if (!sha) return '—'
  return sha.length > 10 ? sha.slice(0, 10) : sha
}