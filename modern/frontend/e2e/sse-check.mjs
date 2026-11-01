// 接口级 SSE 验证：完整事件序列、错误、主动断开、确认卡恢复与执行。
// 需要环境变量 LM_STUDENT_USER / LM_STUDENT_PASS；模型侧可以是真实服务、录制回放或本地 Mock，结果里会打印 meta.mode。
import { Client, readSse } from './api-client.mjs'

const user = process.env.LM_STUDENT_USER, pass = process.env.LM_STUDENT_PASS
if (!user || !pass) { console.error('需要 LM_STUDENT_USER / LM_STUDENT_PASS'); process.exit(2) }
const only = process.argv[2] ?? 'all'
const c = new Client()
await c.login(user, pass)
const results = []
const created = []
const record = (name, pass, detail) => { results.push({ name, pass, detail }); console.log(pass ? 'PASS' : 'FAIL', name, '-', detail) }

async function converse(title, message, { abortAfterDeltas } = {}) {
  const conv = (await c.call('POST', 'ai/conversations', { body: { title } })).data
  created.push(conv.id)
  const ctl = new AbortController()
  const events = []
  let deltas = 0
  const res = await c.raw('POST', `ai/conversations/${conv.id}/stream`, { body: { content: message }, accept: 'text/event-stream', signal: ctl.signal })
  try {
    await readSse(res, (e) => {
      events.push(e)
      if (e.event === 'delta' && abortAfterDeltas && ++deltas >= abortAfterDeltas) ctl.abort()
    })
  } catch (err) {
    if (err.name !== 'AbortError') throw err
  }
  return { conv, status: res.status, contentType: res.headers.get('content-type'), events }
}
const seq = (events) => events.map((e) => e.event).filter((e, i, a) => e !== 'delta' || a[i - 1] !== 'delta').join(' → ')

if (only === 'all' || only === 'search') {
  const r = await converse('SSE 检查：检索', '有没有讲 UNIX 系统编程的书？')
  const meta = r.events.find((e) => e.event === 'meta')?.data
  const result = r.events.find((e) => e.event === 'result')?.data
  const deltaText = r.events.filter((e) => e.event === 'delta').map((e) => e.data.text).join('')
  record('检索问答事件完整', r.status === 200 && seq(r.events).startsWith('meta') && seq(r.events).endsWith('result → done'), `${r.contentType}; mode=${meta?.mode}; ${seq(r.events)}`)
  record('delta 拼接等于 result.content', result && result.content === deltaText, `delta ${r.events.filter((e) => e.event === 'delta').length} 段，${deltaText.length} 字`)
  record('result 带版本引用', (result?.editionReferences?.length ?? 0) > 0, `editionReferences=${JSON.stringify(result?.editionReferences)}`)
  const msgs = (await c.call('GET', `ai/conversations/${r.conv.id}/messages`)).data
  record('消息已持久化', msgs.some((m) => m.role === 'ASSISTANT' || m.role === 'assistant'), `${msgs.length} 条消息`)
}

if (only === 'all' || only === 'renew') {
  const loans = (await c.call('GET', 'me/loans')).data.items.filter((l) => l.status === 'ACTIVE' && !l.overdue)
  if (!loans.length) record('续借确认卡', false, '没有可续借的在借记录')
  else {
    const before = loans[0]
    const r = await converse('SSE 检查：续借', '帮我续借第一本书')
    const result = r.events.find((e) => e.event === 'result')?.data
    const tools = r.events.filter((e) => e.event === 'tool').map((e) => `${e.data.name}:${e.data.status}`)
    const action = result?.actions?.[0]
    record('续借只生成确认卡', action?.status === 'PENDING' && action?.type === 'RENEW', `tools=${tools.join(',')}; action=${action?.type}/${action?.status}`)
    const after = (await c.call('GET', 'me/loans')).data.items.find((l) => l.id === action?.preview?.loanId)
    record('准备阶段不改借阅', after && after.dueAt === (loans.find((l) => l.id === after.id)?.dueAt) && after.renewCount === loans.find((l) => l.id === after.id)?.renewCount, `dueAt=${after?.dueAt} renewCount=${after?.renewCount}`)
    // 刷新恢复：按会话列出确认卡
    const listed = (await c.call('GET', `ai/actions?conversationId=${r.conv.id}`)).data
    record('确认卡可按会话恢复', listed?.some((a) => a.id === action.id && a.status === 'PENDING'), `GET ai/actions → ${listed?.map((a) => `${a.id}:${a.status}`).join(',')}`)
    // 取消 / 再次准备 / 确认
    const cancel = await c.call('POST', `ai/actions/${action.id}/cancel`)
    record('取消确认卡', cancel.ok && cancel.data.status === 'CANCELLED', `status=${cancel.data?.status}`)
    const confirmCancelled = await c.call('POST', `ai/actions/${action.id}/confirm`)
    const still = (await c.call('GET', 'me/loans')).data.items.find((l) => l.id === action.preview.loanId)
    record('已取消的卡确认无效', confirmCancelled.data?.status === 'CANCELLED' && still.renewCount === before.renewCount, `confirm → ${confirmCancelled.status} ${confirmCancelled.data?.status}; renewCount=${still.renewCount}`)
    if (process.env.LM_MUTATE === '1') {
      // 重新准备并点击确认（普通 REST），再次确认应返回同一结果而不重复续借
      const fresh = (await c.call('POST', 'ai/actions/prepare-renew', { body: { loanId: action.preview.loanId, conversationId: r.conv.id } })).data
      const ok = await c.call('POST', `ai/actions/${fresh.id}/confirm`)
      const twice = await c.call('POST', `ai/actions/${fresh.id}/confirm`)
      const renewed = (await c.call('GET', 'me/loans')).data.items.find((l) => l.id === action.preview.loanId)
      record('确认后续借生效且重复确认不重复执行', ok.data?.status === 'SUCCEEDED' && twice.data?.status === 'SUCCEEDED' && renewed.renewCount === before.renewCount + 1, `renewCount ${before.renewCount}→${renewed.renewCount}; dueAt ${before.dueAt.slice(0, 10)}→${renewed.dueAt.slice(0, 10)}`)
    }
  }
}

if (only === 'all' || only === 'borrow') {
  // 通过普通检索找一册在架副本，再在消息里明确写出登录号
  const search = (await c.call('GET', 'search/books?q=' + encodeURIComponent('设计') + '&availableOnly=true&limit=5')).data
  const edition = search?.hits?.[0]?.book
  const copies = edition ? (await c.call('GET', `books/${edition.id}/copies?size=100`)).data : null
  const copy = (copies?.items ?? copies ?? []).find((x) => x.circulationStatus === 'AVAILABLE')
  if (!copy) record('借书确认卡', false, '没有找到在架副本')
  else {
    const r1 = await converse('SSE 检查：借书（无登录号）', `我想借《${edition.title}》`)
    const a1 = r1.events.find((e) => e.event === 'result')?.data?.actions ?? []
    const t1 = r1.events.filter((e) => e.event === 'tool').map((e) => `${e.data.name}:${e.data.status}:${e.data.code}`)
    record('没写登录号时后端拒绝模型自选副本', a1.length === 0 && t1.some((t) => t.startsWith('prepareBorrow:FAILED')), `tools=${t1.join(',')}; actions=${a1.length}`)
    const r = await converse('SSE 检查：借书', `请帮我借 ${copy.accessionNo}`)
    const result = r.events.find((e) => e.event === 'result')?.data
    const action = result?.actions?.[0]
    const tools = r.events.filter((e) => e.event === 'tool').map((e) => `${e.data.name}:${e.data.status}`)
    record('借书只生成确认卡', action?.type === 'BORROW' && action?.status === 'PENDING' && action.preview.accessionNo === copy.accessionNo, `tools=${tools.join(',')}; ${action?.preview?.accessionNo} 《${action?.preview?.title}》`)
    const loansBefore = (await c.call('GET', 'me/loans')).data.items.length
    record('准备阶段未借出', (await c.call('GET', `books/${edition.id}/copies?size=100`)).data.items.find((x) => x.id === copy.id)?.circulationStatus === 'AVAILABLE', `loans=${loansBefore}`)
    if (process.env.LM_MUTATE === '1') {
      const ok = await c.call('POST', `ai/actions/${action.id}/confirm`)
      const loansAfter = (await c.call('GET', 'me/loans')).data.items
      record('点击确认后借出', ok.data?.status === 'SUCCEEDED' && loansAfter.some((l) => l.accessionNo === copy.accessionNo), `status=${ok.data?.status}; loans ${loansBefore}→${loansAfter.length}`)
    }
  }
}

if (only === 'all' || only === 'failure') {
  const r = await converse('SSE 检查：故障', '模拟故障：查一下有什么书')
  const err = r.events.find((e) => e.event === 'error')?.data
  record('模型中途失败时发送 error 事件', !!err && !r.events.some((e) => e.event === 'done' && !r.events.some((x) => x.event === 'error')), `${seq(r.events)}; code=${err?.code}`)
  const msgs = (await c.call('GET', `ai/conversations/${r.conv.id}/messages`)).data
  record('失败轮次不留半截回答', !msgs.some((m) => /ASSISTANT/i.test(m.role)), `${msgs.length} 条消息：${msgs.map((m) => m.role).join(',')}`)
}

if (only === 'all' || only === 'abort') {
  const r = await converse('SSE 检查：断开', '请给我一个长回答', { abortAfterDeltas: 3 })
  record('客户端主动断开', r.events.filter((e) => e.event === 'delta').length === 3 && !r.events.some((e) => e.event === 'done'), seq(r.events))
  await new Promise((ok) => setTimeout(ok, 1500))
  // 断开后会话应可继续使用（租约释放或完成）
  const again = await c.raw('POST', `ai/conversations/${r.conv.id}/stream`, { body: { content: '有没有讲 UNIX 系统编程的书？' }, accept: 'text/event-stream' })
  const ev = []
  await readSse(again, (e) => ev.push(e))
  record('断开后同一会话可继续提问', ev.some((e) => e.event === 'done') || ev.some((e) => e.event === 'error' && e.data.code === 'CONVERSATION_BUSY'), seq(ev) + ' ' + JSON.stringify(ev.find((e) => e.event === 'error')?.data ?? ''))
}

if (only === 'replay') {
  // 后端自带的录制回放（data/replays，真实录制的 qwen3.5-flash 输出），需要以 app.ai.mode=replay 启动
  const q = '请调用queryLibraryPolicy查询默认借阅规则，并引用资料说明最多可以借几本、借期多久。不要查询馆藏，不执行操作。'
  const r = await converse('SSE 检查：录制回放', q)
  const meta = r.events.find((e) => e.event === 'meta')?.data
  const result = r.events.find((e) => e.event === 'result')?.data
  record('回放事件完整且标明 replay', meta?.mode === 'replay' && seq(r.events).endsWith('result → done'), `mode=${meta?.mode}; ${seq(r.events)}; ${JSON.stringify(r.events.find((e) => e.event === 'error')?.data ?? '')}`)
  record('回放回答带引用并标注非实时', (result?.citations?.length ?? 0) > 0 && result.content.startsWith('【录制回放'), `citations=${result?.citations?.map((c) => c.id).join(',')}; ${result?.content?.slice(0, 40)}`)
  const r2 = await converse('SSE 检查：回放未命中', '有没有讲 UNIX 系统编程的书？')
  const err = r2.events.find((e) => e.event === 'error')?.data
  record('未录制的问题明确返回 REPLAY_MISS', err?.code === 'REPLAY_MISS', `${seq(r2.events)}; ${err?.code}`)
}

// 归档本次创建的测试对话（其中未确认的卡会被后端取消），不污染演示账号的对话列表
if (!process.env.LM_KEEP) for (const id of created) await c.call('DELETE', `ai/conversations/${id}`)

console.log(JSON.stringify({ summary: `${results.filter((r) => r.pass).length}/${results.length} 通过` }))
process.exitCode = results.every((r) => r.pass) ? 0 : 1
