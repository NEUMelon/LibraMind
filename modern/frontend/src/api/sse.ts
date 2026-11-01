// POST fetch 读取 SSE。EventSource 不能带 JSON 和 CSRF 头，所以手写解析。
// 处理跨块汉字（流式 TextDecoder）、CRLF、多行 data、注释行；一次 read 不等于一个事件。
import { ApiError, rawFetch } from './http'

export interface SseEvent {
  event: string
  data: string
}

/** 纯解析器：喂入文本块，吐出完整事件。单独导出以便单元测试。 */
export class SseParser {
  private buffer = ''
  private eventName = ''
  private dataLines: string[] = []

  push(chunk: string): SseEvent[] {
    this.buffer += chunk
    const out: SseEvent[] = []
    let index: number
    // 逐行处理；最后一段不完整的行留在 buffer 里等待下一块。
    while ((index = this.findLineEnd()) >= 0) {
      const line = this.buffer.slice(0, index)
      const skip = this.buffer[index] === '\r' && this.buffer[index + 1] === '\n' ? 2 : 1
      this.buffer = this.buffer.slice(index + skip)
      const event = this.line(line)
      if (event) out.push(event)
    }
    return out
  }

  /** 流结束时调用：未以空行结束的残余不视为完整事件。 */
  finish(): SseEvent[] {
    this.buffer = ''
    this.eventName = ''
    this.dataLines = []
    return []
  }

  private findLineEnd(): number {
    const n = this.buffer.indexOf('\n')
    const r = this.buffer.indexOf('\r')
    if (r >= 0 && (n < 0 || r < n)) {
      // 单独的 \r 在块尾时可能是 \r\n 的前半，等待下一块再判断。
      if (r === this.buffer.length - 1) return -1
      return r
    }
    return n
  }

  private line(line: string): SseEvent | null {
    if (line === '') {
      if (this.dataLines.length === 0 && !this.eventName) return null
      const event = { event: this.eventName || 'message', data: this.dataLines.join('\n') }
      this.eventName = ''
      this.dataLines = []
      return event
    }
    if (line.startsWith(':')) return null
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    let value = colon < 0 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') this.eventName = value
    else if (field === 'data') this.dataLines.push(value)
    return null
  }
}

export interface StreamHandlers {
  onEvent: (name: string, data: unknown) => void
}

export type StreamEnd =
  | { kind: 'done' }
  | { kind: 'error'; code: string; message: string; requestId: string }
  /** 连接在 done/error 之前结束：本轮不完整。 */
  | { kind: 'interrupted'; message: string }
  | { kind: 'aborted' }

/**
 * 发送消息并读取完整流。返回值区分：成功完成、服务端 error 事件、断线、用户中止。
 * 若流开始前就返回 JSON 错误（例如 409 CONVERSATION_BUSY），抛出 ApiError。
 */
export async function postStream(
  path: string,
  body: unknown,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<StreamEnd> {
  let response: Response
  try {
    response = await rawFetch('POST', path, { body, signal, accept: 'text/event-stream' })
  } catch (failure) {
    if ((failure as Error)?.name === 'AbortError') return { kind: 'aborted' }
    throw failure
  }
  const contentType = response.headers.get('Content-Type') ?? ''
  if (!response.ok || !contentType.includes('text/event-stream')) {
    let payload: { code?: string; message?: string; requestId?: string } = {}
    try {
      payload = await response.json()
    } catch {
      /* ignore */
    }
    throw new ApiError(
      response.status,
      payload.code ?? `HTTP_${response.status}`,
      payload.message ?? '无法开始对话',
      payload.requestId ?? response.headers.get('X-Request-ID') ?? '',
    )
  }
  if (!response.body) return { kind: 'interrupted', message: '浏览器不支持流式读取' }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  const parser = new SseParser()
  let end: StreamEnd | null = null

  const dispatch = (events: SseEvent[]) => {
    for (const e of events) {
      let data: unknown = e.data
      try {
        data = JSON.parse(e.data)
      } catch {
        /* 保留原文 */
      }
      if (e.event === 'done' && end?.kind !== 'error') end = { kind: 'done' }
      if (e.event === 'error') {
        const d = (data ?? {}) as { code?: string; message?: string; requestId?: string }
        end = {
          kind: 'error',
          code: d.code ?? 'AI_UNAVAILABLE',
          message: d.message ?? '本轮回答未完成',
          requestId: d.requestId ?? '',
        }
      }
      handlers.onEvent(e.event, data)
    }
  }

  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      dispatch(parser.push(decoder.decode(value, { stream: true })))
    }
    dispatch(parser.push(decoder.decode()))
    parser.finish()
  } catch (failure) {
    if ((failure as Error)?.name === 'AbortError') return { kind: 'aborted' }
    return end ?? { kind: 'interrupted', message: '连接中断，本轮回答不完整' }
  }
  return end ?? { kind: 'interrupted', message: '连接在回答完成前结束，本轮回答不完整' }
}
