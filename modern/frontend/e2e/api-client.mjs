// e2e 用的最小 API 客户端：Session Cookie + CSRF，与前端 src/api/http.ts 的约定一致。
export class Client {
  constructor(base = process.env.LM_BASE ?? 'http://127.0.0.1:8090') {
    this.base = base
    this.cookies = new Map()
    this.csrf = null
  }
  cookieHeader() {
    return [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ')
  }
  keep(res) {
    for (const c of res.headers.getSetCookie?.() ?? []) {
      const [pair] = c.split(';')
      const i = pair.indexOf('=')
      this.cookies.set(pair.slice(0, i), pair.slice(i + 1))
    }
  }
  async raw(method, path, { body, headers = {}, accept = 'application/json', signal } = {}) {
    const h = { accept, cookie: this.cookieHeader(), ...headers }
    if (body !== undefined) h['content-type'] = 'application/json'
    if (method !== 'GET' && this.csrf) h[this.csrf.headerName] = this.csrf.token
    const res = await fetch(this.base + '/api/v1/' + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), signal })
    this.keep(res)
    return res
  }
  async refreshCsrf() {
    const res = await this.raw('GET', 'auth/csrf')
    this.csrf = (await res.json()).data
  }
  async call(method, path, opts) {
    const res = await this.raw(method, path, opts)
    const json = await res.json().catch(() => null)
    return { status: res.status, ok: res.ok, data: json?.data, error: json, json }
  }
  async login(username, password) {
    await this.refreshCsrf()
    const r = await this.call('POST', 'auth/login', { body: { username, password } })
    if (!r.ok) throw new Error('login failed ' + r.status + ' ' + JSON.stringify(r.error))
    await this.refreshCsrf()
    return r.data
  }
}

// 解析 POST 返回的 SSE 流；onEvent 收到 {event, data}
export async function readSse(res, onEvent) {
  const reader = res.body.getReader()
  const dec = new TextDecoder()
  let buf = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buf += dec.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    let i
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, i)
      buf = buf.slice(i + 2)
      let event = 'message'
      const data = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
      }
      if (!data.length) continue
      let parsed = data.join('\n')
      try { parsed = JSON.parse(parsed) } catch { /* 保留文本 */ }
      onEvent({ event, data: parsed })
    }
  }
}
