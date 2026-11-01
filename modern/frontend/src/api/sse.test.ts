import { afterEach, describe, expect, it, vi } from 'vitest'
import { SseParser, postStream } from './sse'
import { clearCsrf } from './http'

describe('SseParser', () => {
  it('跨块拼接事件，一次 push 不等于一个事件', () => {
    const p = new SseParser()
    expect(p.push('event: delta\nda')).toEqual([])
    expect(p.push('ta: {"text":"你"}\n')).toEqual([])
    expect(p.push('\nevent: done\ndata: {}\n\n')).toEqual([
      { event: 'delta', data: '{"text":"你"}' },
      { event: 'done', data: '{}' },
    ])
  })

  it('处理 CRLF，包括 \\r 与 \\n 落在两个块里', () => {
    const p = new SseParser()
    expect(p.push('event: meta\r')).toEqual([])
    expect(p.push('\ndata: 1\r\n\r\n')).toEqual([{ event: 'meta', data: '1' }])
  })

  it('多行 data 用换行连接，忽略注释行，缺省事件名为 message', () => {
    const p = new SseParser()
    expect(p.push(': keep-alive\ndata: a\ndata: b\n\n')).toEqual([{ event: 'message', data: 'a\nb' }])
  })

  it('未以空行结束的残余不算完整事件', () => {
    const p = new SseParser()
    expect(p.push('event: delta\ndata: {"text":"半')).toEqual([])
    expect(p.finish()).toEqual([])
  })
})

function streamResponse(chunks: Uint8Array[], { close = true } = {}) {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((c) => controller.enqueue(c))
      if (close) controller.close()
      else controller.error(new TypeError('network error'))
    },
  })
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}
function mockFetch(stream: Response) {
  const calls: { url: string; init: RequestInit }[] = []
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    calls.push({ url, init })
    if (url.endsWith('/auth/csrf')) return Response.json({ success: true, data: { headerName: 'X-CSRF-TOKEN', token: 't1' } })
    return stream
  })
  return calls
}

describe('postStream', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    clearCsrf()
  })

  it('POST 带 JSON、CSRF 头和 text/event-stream，汉字在字节边界被切开也能还原', async () => {
    const bytes = new TextEncoder().encode('event: delta\ndata: {"text":"图书馆"}\n\nevent: done\ndata: {"completed":true}\n\n')
    // 在“图”的 3 个字节中间切开
    const cut = bytes.indexOf(0xe5) + 1
    const calls = mockFetch(streamResponse([bytes.slice(0, cut), bytes.slice(cut)]))
    const seen: [string, unknown][] = []
    const end = await postStream('/api/v1/ai/conversations/1/stream', { content: '你好' }, { onEvent: (n, d) => seen.push([n, d]) })
    expect(end).toEqual({ kind: 'done' })
    expect(seen[0]).toEqual(['delta', { text: '图书馆' }])
    const post = calls.find((c) => c.init?.method === 'POST')!
    const h = post.init.headers as Record<string, string>
    expect(h.Accept).toBe('text/event-stream')
    expect(h['X-CSRF-TOKEN']).toBe('t1')
    expect(JSON.parse(String(post.init.body))).toEqual({ content: '你好' })
  })

  it('error 事件之后即使再收到 done 也按失败处理', async () => {
    mockFetch(streamResponse([new TextEncoder().encode('event: error\ndata: {"code":"AI_UNAVAILABLE","message":"x","requestId":"r1"}\n\nevent: done\ndata: {}\n\n')]))
    const end = await postStream('/x', {}, { onEvent: () => {} })
    expect(end).toMatchObject({ kind: 'error', code: 'AI_UNAVAILABLE', requestId: 'r1' })
  })

  it('没有 done/error 就结束算作不完整', async () => {
    mockFetch(streamResponse([new TextEncoder().encode('event: delta\ndata: {"text":"a"}\n\n')]))
    expect((await postStream('/x', {}, { onEvent: () => {} })).kind).toBe('interrupted')
  })

  it('读取中网络错误算作不完整', async () => {
    mockFetch(streamResponse([new TextEncoder().encode('event: delta\ndata: {"text":"a"}\n\n')], { close: false }))
    expect((await postStream('/x', {}, { onEvent: () => {} })).kind).toBe('interrupted')
  })
})
