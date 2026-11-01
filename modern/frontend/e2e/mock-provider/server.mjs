// 本地模拟的 OpenAI 兼容模型服务，仅用于在无法访问 aihubmix.com 的环境里验证 SSE、工具调用和确认卡链路。
// 它不是模型：回答由下面的固定脚本按关键词生成，所有结果都必须标注为 Mock。
// 不记录请求头（避免记录任何 Authorization），只记录路径、轮次和场景名。
import https from 'node:https'
import fs from 'node:fs'

const PORT = Number(process.env.MOCK_PORT ?? 443)
const CERT_DIR = process.env.MOCK_CERT_DIR ?? new URL('./certs/', import.meta.url).pathname
const log = (...a) => console.log(new Date().toISOString(), ...a)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function lastUser(messages) {
  for (let i = messages.length - 1; i >= 0; i--) if (messages[i].role === 'user') return String(messages[i].content ?? '')
  return ''
}
// 本轮（最后一条 user 之后）的工具结果
function toolResults(messages) {
  const out = []
  for (let i = messages.length - 1; i >= 0 && messages[i].role !== 'user'; i--) {
    if (messages[i].role === 'tool') {
      let v = messages[i].content
      try { v = JSON.parse(v) } catch { /* 保留原文 */ }
      out.unshift({ id: messages[i].tool_call_id, content: v })
    }
  }
  return out
}
function calledTools(messages) {
  const names = []
  for (let i = messages.length - 1; i >= 0 && messages[i].role !== 'user'; i--) {
    for (const c of messages[i].tool_calls ?? []) names.unshift(c.function?.name)
  }
  return names
}

// 返回 { tool: {name, args} } 或 { text, delay, fail }
function plan(body) {
  const msgs = body.messages ?? []
  const q = lastUser(msgs)
  const done = calledTools(msgs)
  const results = toolResults(msgs)
  const acc = q.match(/LM-\d{13}-\d{2}/)?.[0]

  if (q.includes('模拟故障')) return { scene: 'failure', text: '正在查询馆藏，请稍候……这一段之后连接会被模拟服务主动切断。', fail: true, delay: 120 }
  if (q.includes('长回答')) {
    const para = Array.from({ length: 40 }, (_, i) => `第 ${i + 1} 段：这是模拟服务逐段输出的文字，用来验证前端在长回答过程中停止生成、断开连接后的状态。`).join('\n\n')
    return { scene: 'long', text: para, delay: 200 }
  }
  if (acc) {
    if (!done.includes('prepareBorrow')) return { scene: 'borrow', tool: { name: 'prepareBorrow', args: { accessionNo: acc } } }
    const r = results.at(-1)?.content
    if (r?.code) return { scene: 'borrow', text: `没能准备借阅：${r.message ?? r.code}。请到图书详情或流通台核对馆藏登录号。` }
    return { scene: 'borrow', text: `已为你准备好借阅 **${acc}** 的确认卡。请核对书名和应还日期，**点击卡片上的“确认借阅”** 才会真正登记；不点确认不会借出。` }
  }
  if (/续借|还书|归还/.test(q)) {
    const type = /续借/.test(q) ? 'prepareRenew' : 'prepareReturn'
    if (!done.includes('getMyLoans')) return { scene: type, tool: { name: 'getMyLoans', args: {} } }
    if (!done.includes(type)) {
      const loans = results.find((r) => Array.isArray(r.content?.items))?.content.items ?? []
      if (!loans.length) return { scene: type, text: '你目前没有在借的图书，不需要续借或归还。' }
      if (loans.length > 1 && !/第一本|第1本/.test(q)) {
        const list = loans.map((l, i) => `${i + 1}. 《${l.title}》 应还 ${String(l.dueAt).slice(0, 10)}`).join('\n')
        return { scene: type, text: `你现在借了 ${loans.length} 本书：\n\n${list}\n\n请告诉我要处理哪一本（例如“续借第一本”）。` }
      }
      return { scene: type, tool: { name: type, args: { loanId: loans[0].id } } }
    }
    const r = results.at(-1)?.content
    if (r?.code) return { scene: type, text: `没能准备确认卡：${r.message ?? r.code}` }
    return { scene: type, text: `确认卡已准备好：《${r?.preview?.title ?? ''}》。请点击卡片上的按钮完成操作，口头回复“确认”不会生效。` }
  }
  // “我想借《某书》”但没给登录号：故意让模拟模型自作主张挑一册，用来验证后端会拒绝
  if (/借/.test(q)) {
    if (!done.includes('searchBooks')) return { scene: 'borrow-guess', tool: { name: 'searchBooks', args: { query: q.replace(/我想借|帮我借|[《》]/g, '').slice(0, 60) } } }
    const hit = results.find((r) => Array.isArray(r.content?.hits))?.content.hits?.[0]
    if (hit && !done.includes('prepareBorrow')) return { scene: 'borrow-guess', tool: { name: 'prepareBorrow', args: { accessionNo: `LM-${(hit.book ?? hit).isbn13}-01` } } }
    const r = results.at(-1)?.content
    return { scene: 'borrow-guess', text: r?.code ? `需要你提供要借的那一册的馆藏登录号（贴在书上的条码）。可以在图书详情里查看在架的副本。` : '已准备确认卡。' }
  }
  // 默认：检索后列出结果
  if (!done.includes('searchBooks')) return { scene: 'search', tool: { name: 'searchBooks', args: { query: q.slice(0, 60) } } }
  const hits = results.find((r) => Array.isArray(r.content?.hits))?.content.hits ?? []
  if (!hits.length) return { scene: 'search', text: '馆藏里没有找到相关的书。可以换个关键词，或者到“找书”页面按书名、作者检索。' }
  const lines = hits.slice(0, 5).map((h, i) => {
    const b = h.book ?? h
    return `${i + 1}. **《${b.title}》** ${b.authors ?? ''}，${b.publisher ?? ''}${b.availableCount > 0 ? `，现有 ${b.availableCount} 册可借` : '，暂无可借'}`
  })
  return { scene: 'search', text: `馆藏中找到 ${hits.length} 本相关的书：\n\n${lines.join('\n')}\n\n需要借哪一本，请在图书详情里选择具体的馆藏登录号。` }
}

function chunk(id, delta, finish = null) {
  return { id, object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000), model: 'qwen3.5-flash', choices: [{ index: 0, delta, finish_reason: finish }] }
}
const usage = (id, p, c) => ({ id, object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000), model: 'qwen3.5-flash', choices: [], usage: { prompt_tokens: p, completion_tokens: c, total_tokens: p + c } })

async function streamReply(req, res, body) {
  const p = plan(body)
  const id = 'mock-' + Math.random().toString(36).slice(2, 10)
  const promptTokens = Math.ceil(JSON.stringify(body.messages ?? []).length / 2)
  log('chat', p.scene, p.tool ? `tool=${p.tool.name}` : `text=${p.text?.length}`, body.stream ? 'stream' : 'sync')
  if (!body.stream) {
    const message = p.tool
      ? { role: 'assistant', content: null, tool_calls: [{ id: 'call_' + id, type: 'function', function: { name: p.tool.name, arguments: JSON.stringify(p.tool.args) } }] }
      : { role: 'assistant', content: p.text }
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ id, object: 'chat.completion', created: Math.floor(Date.now() / 1000), model: 'qwen3.5-flash', choices: [{ index: 0, message, finish_reason: p.tool ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: promptTokens, completion_tokens: 20, total_tokens: promptTokens + 20 } }))
    return
  }
  res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' })
  const send = (o) => res.write(`data: ${JSON.stringify(o)}\n\n`)
  let closed = false
  res.on('close', () => { closed = true })
  if (p.tool) {
    send(chunk(id, { role: 'assistant', content: null, tool_calls: [{ index: 0, id: 'call_' + id, type: 'function', function: { name: p.tool.name, arguments: '' } }] }))
    const args = JSON.stringify(p.tool.args)
    for (let i = 0; i < args.length; i += 12) send(chunk(id, { tool_calls: [{ index: 0, function: { arguments: args.slice(i, i + 12) } }] }))
    send(chunk(id, {}, 'tool_calls'))
    send(usage(id, promptTokens, 18))
    res.end('data: [DONE]\n\n')
    return
  }
  send(chunk(id, { role: 'assistant', content: '' }))
  const parts = Array.from(p.text)
  let sent = 0
  for (let i = 0; i < parts.length && !closed; i += 6) {
    send(chunk(id, { content: parts.slice(i, i + 6).join('') }))
    sent++
    await sleep(p.delay ?? 35)
    if (p.fail && sent === 4) {
      log('chat', 'failure: destroying socket mid-stream')
      res.socket?.destroy()
      return
    }
  }
  if (closed) { log('chat', p.scene, 'client closed early'); return }
  send(chunk(id, {}, 'stop'))
  send(usage(id, promptTokens, parts.length))
  res.end('data: [DONE]\n\n')
}

const server = https.createServer({ key: fs.readFileSync(CERT_DIR + 'server.key'), cert: fs.readFileSync(CERT_DIR + 'server.crt') }, (req, res) => {
  let raw = ''
  req.on('data', (d) => (raw += d))
  req.on('end', async () => {
    let body = {}
    try { body = JSON.parse(raw || '{}') } catch { /* ignore */ }
    if (req.method === 'POST' && req.url.endsWith('/chat/completions')) return streamReply(req, res, body)
    if (req.method === 'POST' && req.url.endsWith('/embeddings')) {
      const inputs = Array.isArray(body.input) ? body.input : [body.input]
      log('embeddings', inputs.length)
      const dim = body.dimensions ?? 1024
      const data = inputs.map((text, index) => {
        // 确定性伪向量：只用于让链路跑通，不具备语义。
        let h = 2166136261
        const v = Array.from({ length: dim }, (_, i) => { h = Math.imul(h ^ (String(text).charCodeAt(i % Math.max(1, String(text).length)) + i), 16777619); return ((h >>> 0) / 4294967295) - 0.5 })
        const n = Math.hypot(...v)
        return { object: 'embedding', index, embedding: v.map((x) => x / n) }
      })
      res.writeHead(200, { 'content-type': 'application/json' })
      return res.end(JSON.stringify({ object: 'list', model: 'text-embedding-v4', data, usage: { prompt_tokens: 10 * inputs.length, total_tokens: 10 * inputs.length } }))
    }
    log('404', req.method, req.url)
    res.writeHead(404, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ error: { message: 'mock: not found' } }))
  })
})
server.listen(PORT, '127.0.0.1', () => log(`mock provider on https://127.0.0.1:${PORT} (pretending to be aihubmix.com)`))
