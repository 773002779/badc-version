export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  traceId?: string
}

export class ApiError extends Error {
  code: number
  detail?: unknown
  traceId?: string

  constructor(code: number, message: string, detail?: unknown, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.detail = detail
    this.traceId = traceId
  }

  /** True when the failure is "backend not reachable" rather than a business rejection. */
  get isOffline(): boolean {
    return this.code === 0
  }
}

const USER_KEY = 'rp.userId'

export function currentUser(): string {
  return localStorage.getItem(USER_KEY) || 'dev'
}

export function setCurrentUser(userId: string) {
  localStorage.setItem(USER_KEY, userId.trim() || 'dev')
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  let res: Response
  try {
    res = await fetch(path, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        'X-User-Id': currentUser(),
        ...(init.headers ?? {})
      }
    })
  } catch (e) {
    throw new ApiError(0, '无法连接后端服务（请确认已运行 scripts\\start.ps1）')
  }

  let body: ApiEnvelope<T> | null = null
  try {
    body = (await res.json()) as ApiEnvelope<T>
  } catch {
    body = null
  }

  if (!res.ok) {
    throw new ApiError(res.status, `请求失败（HTTP ${res.status}）`)
  }
  if (!body) {
    throw new ApiError(res.status, '响应不是合法的 JSON')
  }
  if (body.code !== 0) {
    throw new ApiError(body.code, body.message || '业务错误', body.data, body.traceId)
  }
  return body.data
}

export function qs(params: Record<string, string | number | undefined | null>): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
  return parts.length ? `?${parts.join('&')}` : ''
}