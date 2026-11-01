// 统一的 HTTP 层：Session Cookie + CSRF、错误归一、幂等键。
// 浏览器管理 Cookie；这里只在内存里保存 CSRF Token，不落 localStorage。

export const API_BASE = '/api/v1'

export class ApiError extends Error {
  status: number
  code: string
  requestId: string
  details: Record<string, string> | null
  /** 写请求在网络层失败：服务器可能已经执行，结果不明。 */
  uncertain: boolean

  constructor(
    status: number,
    code: string,
    message: string,
    requestId = '',
    details: Record<string, string> | null = null,
    uncertain = false,
  ) {
    super(message)
    this.status = status
    this.code = code
    this.requestId = requestId
    this.details = details
    this.uncertain = uncertain
  }
}

interface CsrfState {
  headerName: string
  token: string
}

let csrf: CsrfState | null = null
let csrfPending: Promise<CsrfState> | null = null

export async function refreshCsrf(): Promise<CsrfState> {
  if (!csrfPending) {
    csrfPending = fetch(`${API_BASE}/auth/csrf`, { credentials: 'same-origin', cache: 'no-store' })
      .then(async (r) => {
        const body = await r.json()
        if (!r.ok || !body?.success) throw new ApiError(r.status, body?.code ?? 'CSRF_UNAVAILABLE', '无法建立会话')
        csrf = { headerName: body.data.headerName, token: body.data.token }
        return csrf
      })
      .finally(() => {
        csrfPending = null
      })
  }
  return csrfPending
}

export function clearCsrf() {
  csrf = null
}

type Listener = (error: ApiError) => void
const unauthorizedListeners = new Set<Listener>()
export function onUnauthorized(listener: Listener) {
  unauthorizedListeners.add(listener)
  return () => unauthorizedListeners.delete(listener)
}

export type Query = Record<string, string | number | boolean | null | undefined>

export interface RequestOptions {
  query?: Query
  body?: unknown
  form?: FormData
  idempotencyKey?: string
  signal?: AbortSignal
  /** 默认 application/json；SSE 需要 text/event-stream，否则后端返回 406。 */
  accept?: string
  /** 不触发全局 401 处理（登录页自己处理）。 */
  silent401?: boolean
}

export function buildUrl(path: string, query?: Query) {
  const url = path.startsWith('/') ? path : `${API_BASE}/${path}`
  if (!query) return url
  const params = new URLSearchParams()
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === '') continue
    params.set(k, String(v))
  }
  const qs = params.toString()
  return qs ? `${url}?${qs}` : url
}

async function parseError(response: Response): Promise<ApiError> {
  let body: { code?: string; message?: string; requestId?: string; details?: Record<string, string> } = {}
  try {
    body = await response.json()
  } catch {
    /* 非 JSON 响应 */
  }
  const requestId = body.requestId ?? response.headers.get('X-Request-ID') ?? ''
  return new ApiError(
    response.status,
    body.code ?? `HTTP_${response.status}`,
    body.message ?? defaultMessage(response.status),
    requestId,
    body.details ?? null,
  )
}

function defaultMessage(status: number) {
  if (status === 401) return '请先登录'
  if (status === 403) return '没有权限执行此操作'
  if (status === 404) return '记录不存在或无权查看'
  if (status === 413) return '文件超过上传上限'
  if (status >= 500) return '服务器暂时无法完成请求'
  return '请求失败'
}

export async function rawFetch(method: string, path: string, opts: RequestOptions = {}, retried = false): Promise<Response> {
  const isWrite = method !== 'GET' && method !== 'HEAD'
  const headers: Record<string, string> = { Accept: opts.accept ?? 'application/json' }
  if (isWrite) {
    const state = csrf ?? (await refreshCsrf())
    headers[state.headerName] = state.token
  }
  if (opts.idempotencyKey) headers['Idempotency-Key'] = opts.idempotencyKey
  let body: BodyInit | undefined
  if (opts.form) {
    body = opts.form
  } else if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(opts.body)
  }

  let response: Response
  try {
    response = await fetch(buildUrl(path, opts.query), {
      method,
      headers,
      body,
      credentials: 'same-origin',
      signal: opts.signal,
    })
  } catch (failure) {
    if ((failure as Error)?.name === 'AbortError') throw failure
    throw new ApiError(
      0,
      'NETWORK_ERROR',
      isWrite ? '网络中断，操作结果不明，请核对后再继续' : '网络连接失败，请稍后重试',
      '',
      null,
      isWrite,
    )
  }

  if (response.status === 403 && isWrite && !retried) {
    const peek = await response.clone().json().catch(() => null)
    if (peek?.code === 'CSRF_TOKEN_INVALID') {
      // 请求在过滤器阶段被拒绝，业务未执行；刷新 Token 后用同一请求体和同一幂等键重发一次。
      await refreshCsrf()
      return rawFetch(method, path, opts, true)
    }
  }
  return response
}

export async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
  const response = await rawFetch(method, path, opts)
  if (!response.ok) {
    const error = await parseError(response)
    if (error.status === 401 && !opts.silent401) unauthorizedListeners.forEach((l) => l(error))
    throw error
  }
  const body = await response.json()
  if (!body.success) {
    throw new ApiError(response.status, body.code ?? 'REQUEST_FAILED', body.message ?? '请求失败', body.requestId ?? '')
  }
  return body.data as T
}

export const http = {
  get: <T>(path: string, query?: Query, signal?: AbortSignal) => request<T>('GET', path, { query, signal }),
  post: <T>(path: string, body?: unknown, extra: Omit<RequestOptions, 'body'> = {}) =>
    request<T>('POST', path, { ...extra, body }),
  patch: <T>(path: string, body: unknown) => request<T>('PATCH', path, { body }),
  delete: <T>(path: string, body?: unknown) => request<T>('DELETE', path, { body }),
  upload: <T>(path: string, file: File | Blob, field = 'file') => {
    const form = new FormData()
    form.append(field, file)
    return request<T>('POST', path, { form })
  },
}

export function newIdempotencyKey(): string {
  const c: Crypto = globalThis.crypto
  // 非安全上下文（例如用局域网IP访问开发服务器）没有 randomUUID，回退到 getRandomValues。
  if (typeof c.randomUUID === 'function') return c.randomUUID()
  const bytes = new Uint8Array(16)
  c.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/**
 * 一次用户意图 = 一个幂等键 + 固定请求体。
 * 网络失败后 retry() 使用同一个 key 和同一个 body；修改输入必须新建 Intent。
 */
export class Intent<T> {
  readonly key = newIdempotencyKey()
  readonly method: string
  readonly path: string
  readonly body: unknown
  constructor(method: string, path: string, body: unknown) {
    this.method = method
    this.path = path
    // 按 JSON 深拷贝：请求体本来就要序列化成 JSON；structuredClone 不能复制 Vue 的响应式代理。
    this.body = body === undefined ? undefined : JSON.parse(JSON.stringify(body))
  }
  send(): Promise<T> {
    return request<T>(this.method, this.path, { body: this.body, idempotencyKey: this.key })
  }
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return '发生未知错误'
}
