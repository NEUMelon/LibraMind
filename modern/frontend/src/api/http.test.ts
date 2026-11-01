import { afterEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { ApiError, Intent, clearCsrf, request } from './http'

type Call = { url: string; method: string; headers: Record<string, string>; body: string | undefined }

function server(handler: (c: Call, n: number) => Response) {
  const calls: Call[] = []
  let csrfN = 0
  vi.stubGlobal('fetch', async (url: string, init: RequestInit = {}) => {
    if (url.endsWith('/auth/csrf')) {
      csrfN++
      return Response.json({ success: true, data: { headerName: 'X-CSRF-TOKEN', token: `t${csrfN}` } })
    }
    const c = { url, method: init.method ?? 'GET', headers: (init.headers ?? {}) as Record<string, string>, body: init.body as string | undefined }
    calls.push(c)
    return handler(c, calls.length)
  })
  return calls
}
const ok = (data: unknown) => Response.json({ success: true, data, requestId: 'r' })

describe('http', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    clearCsrf()
  })

  it('Intent 重发使用同一幂等键和同一请求体', async () => {
    const calls = server((_, n) => (n === 1 ? new Response(null, { status: 502 }) : ok({ id: 1 })))
    const body = { studentId: 1, accessionNos: ['LM-1'] }
    const intent = new Intent<{ id: number }>('POST', 'loans', body)
    body.accessionNos.push('LM-2') // 之后修改原对象不影响重发
    await expect(intent.send()).rejects.toBeInstanceOf(ApiError)
    await expect(intent.send()).resolves.toEqual({ id: 1 })
    expect(calls).toHaveLength(2)
    expect(calls[0].headers['Idempotency-Key']).toBe(intent.key)
    expect(calls[1].headers['Idempotency-Key']).toBe(intent.key)
    expect(calls[0].body).toBe(calls[1].body)
    expect(JSON.parse(calls[1].body!)).toEqual({ studentId: 1, accessionNos: ['LM-1'] })
  })

  it('Intent 可以接收 Vue 响应式对象作为请求体', async () => {
    const calls = server(() => ok({ created: 1 }))
    const state = reactive({ books: [{ title: 'A', isbn13: '9787115147318' }], copiesPerEdition: 1 })
    const intent = new Intent('POST', 'import/books/execute', { books: state.books, copiesPerEdition: state.copiesPerEdition })
    state.books[0].title = 'B'
    await intent.send()
    expect(JSON.parse(calls[0].body!).books[0].title).toBe('A')
  })

  it('CSRF 失效时刷新 Token 只重试一次，权限拒绝不重试', async () => {
    const calls = server((c) =>
      c.headers['X-CSRF-TOKEN'] === 't1'
        ? Response.json({ success: false, code: 'CSRF_TOKEN_INVALID', message: 'csrf' }, { status: 403 })
        : ok(true),
    )
    await expect(request('POST', 'x', { body: {} })).resolves.toBe(true)
    expect(calls.map((c) => c.headers['X-CSRF-TOKEN'])).toEqual(['t1', 't2'])

    const denied = server(() => Response.json({ success: false, code: 'ACCESS_DENIED', message: 'no' }, { status: 403 }))
    await expect(request('POST', 'y', { body: {} })).rejects.toMatchObject({ code: 'ACCESS_DENIED', status: 403 })
    expect(denied).toHaveLength(1)
  })

  it('写请求网络失败标记为结果不明，读请求不标记', async () => {
    vi.stubGlobal('fetch', async (url: string) => {
      if (url.endsWith('/auth/csrf')) return Response.json({ success: true, data: { headerName: 'X-CSRF-TOKEN', token: 't' } })
      throw new TypeError('Failed to fetch')
    })
    await expect(request('POST', 'loans', { body: {} })).rejects.toMatchObject({ code: 'NETWORK_ERROR', uncertain: true })
    await expect(request('GET', 'loans')).rejects.toMatchObject({ code: 'NETWORK_ERROR', uncertain: false })
  })
})
