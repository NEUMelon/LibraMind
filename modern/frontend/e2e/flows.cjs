// 浏览器端到端流程：管理员（桌面）与学生（手机尺寸）两个角色，连真实后端。
// 运行：LM_ADMIN_USER=… LM_ADMIN_PASS=… node e2e/flows.cjs
// 可选：LM_WEB（前端地址，默认 http://127.0.0.1:5173）、LM_API（后端，默认 http://127.0.0.1:8090）、
//       LM_AI=mock|replay|live|off（当前模型侧状态；mock 时截图右上角会加“Mock 模型”标记，避免被误认为真实 AI）。
// 流程会新建一名测试学生和一个测试版本，结束时归还借出的书、停用测试学生、归档测试版本。
const { chromium } = require('playwright')
const fs = require('fs')
const path = require('path')

const WEB = process.env.LM_WEB ?? 'http://127.0.0.1:5173'
const AI = process.env.LM_AI ?? 'off'
const ADMIN = { u: process.env.LM_ADMIN_USER, p: process.env.LM_ADMIN_PASS }
if (!ADMIN.u || !ADMIN.p) {
  console.error('需要 LM_ADMIN_USER / LM_ADMIN_PASS')
  process.exit(2)
}
const stamp = new Date().toISOString().replace(/[-:]/g, '').slice(0, 15)
const OUT = path.join(__dirname, 'output', stamp)
fs.mkdirSync(OUT, { recursive: true })

const results = []
let shotN = 0
async function shot(page, name, opts = {}) {
  const file = `${String(++shotN).padStart(2, '0')}-${name}.png`
  await page.screenshot({ path: path.join(OUT, file), ...opts })
  return file
}
async function step(page, name, fn) {
  const t0 = Date.now()
  try {
    const detail = (await fn()) ?? ''
    results.push({ name, ok: true, detail, ms: Date.now() - t0 })
    console.log('PASS', name, detail)
  } catch (e) {
    let file = ''
    try { file = await shot(page, 'FAIL-' + name.replace(/[^\w一-龥]+/g, '_').slice(0, 40)) } catch { /* ignore */ }
    results.push({ name, ok: false, detail: String(e.message ?? e).split('\n')[0] + (file ? ` (截图 ${file})` : ''), ms: Date.now() - t0 })
    console.log('FAIL', name, e.message)
  }
}
function must(cond, msg) {
  if (!cond) throw new Error(msg)
}
async function markMock(page) {
  if (AI !== 'mock') return
  await page.addStyleTag({ content: 'body::after{content:"Mock 模型 · 非真实 AI";position:fixed;top:8px;right:8px;z-index:9999;padding:3px 8px;border-radius:4px;background:#b42318;color:#fff;font:600 12px/1.4 system-ui;pointer-events:none}' })
}
async function login(page, u, p) {
  await page.goto(WEB + '/login')
  await page.fill('#u', u)
  await page.fill('#p', p)
  await page.click('button[type=submit]')
  await page.waitForURL(/\/(desk|shelf)/)
}
function isbn13(prefix12) {
  const s = [...prefix12].reduce((a, d, i) => a + Number(d) * (i % 2 ? 3 : 1), 0)
  return prefix12 + ((10 - (s % 10)) % 10)
}

;(async () => {
  const { Client } = await import('./api-client.mjs')
  const api = new Client(process.env.LM_API)
  await api.login(ADMIN.u, ADMIN.p)
  const browser = await chromium.launch()
  const consoleErrors = []

  // ------------------------------------------------------------------ 管理员
  const actx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' })
  const a = await actx.newPage()
  a.on('pageerror', (e) => consoleErrors.push('admin: ' + e.message))
  a.on('dialog', (d) => d.dismiss())

  const studentNo = '2099' + stamp.slice(9, 15)
  const stuUser = 'e2e' + stamp.slice(4, 15).replace('T', '')
  const stuPass = 'E2e-' + Math.random().toString(36).slice(2, 10) + 'A1'
  let studentId = null
  let testEdition = null
  let borrowCopy = null

  await step(a, '管理员登录后进入流通台', async () => {
    await a.goto(WEB + '/login')
    await shot(a, 'login')
    await login(a, ADMIN.u, ADMIN.p)
    must(a.url().endsWith('/desk'), '未进入 /desk：' + a.url())
    return a.url()
  })

  await step(a, '新增学生并开通账号', async () => {
    await a.goto(WEB + '/students')
    await a.click('text=新增学生')
    await a.fill('#sf-no', studentNo)
    await a.fill('#sf-name', 'E2E 测试学生')
    await a.fill('#sf-college', '信息管理学院')
    await a.fill('#sf-major', '图书馆学')
    await a.click('button[form=student-form]')
    await a.waitForURL(/id=\d+/)
    studentId = Number(new URL(a.url()).searchParams.get('id'))
    await a.click('text=开通账号')
    await a.fill('#acc-u', stuUser)
    await a.fill('#acc-p', stuPass)
    await a.click('button[form=acc-form]')
    await a.waitForTimeout(800)
    await shot(a, 'student-drawer')
    const s = await api.call('GET', `students/${studentId}`)
    must(s.ok && s.data.studentNo === studentNo, '后端没有这名学生')
    return `学生 #${studentId} ${studentNo}，账号 ${stuUser}`
  })

  await step(a, '新增出版版本与副本', async () => {
    const isbn = isbn13('979' + stamp.slice(6, 15).replace('T', '').padEnd(9, '0').slice(0, 9))
    await a.goto(WEB + '/books')
    await a.click('text=新增版本')
    await a.fill('#bf-title', 'E2E 测试版本：信息组织')
    await a.fill('#bf-authors', '测试作者')
    await a.fill('#bf-isbn13', isbn)
    await a.fill('#bf-publisher', '测试出版社')
    await a.click('button[form=book-form]')
    await a.waitForURL(/id=\d+/)
    testEdition = Number(new URL(a.url()).searchParams.get('id'))
    await a.waitForTimeout(600)
    await a.click('text=新增副本')
    await a.fill('#cf-loc', '二层综合书库')
    await a.click('button[form=copy-form]')
    await a.waitForTimeout(900)
    await shot(a, 'edition-with-copy')
    const copies = await api.call('GET', `books/${testEdition}/copies?size=10`)
    must(copies.data.items.length === 1, '副本数不是 1')
    return `版本 #${testEdition} ISBN ${isbn}，副本 ${copies.data.items[0].accessionNo}`
  })

  await step(a, '流通台：扫学号、扫登录号、借出', async () => {
    const found = (await api.call('GET', 'search/books?q=' + encodeURIComponent('程序') + '&availableOnly=true&limit=10')).data.hits
    for (const h of found) {
      const cs = (await api.call('GET', `books/${h.book.id}/copies?size=100`)).data.items
      borrowCopy = cs.find((c) => c.circulationStatus === 'AVAILABLE')
      if (borrowCopy) break
    }
    must(borrowCopy, '找不到在架副本')
    await a.goto(WEB + '/desk')
    await a.fill('input[aria-label=学号]', studentNo)
    await a.keyboard.press('Enter')
    await a.waitForSelector('.reader')
    await a.fill('input[aria-label=馆藏登录号]', borrowCopy.accessionNo)
    await a.keyboard.press('Enter')
    await a.waitForSelector('text=可借')
    await shot(a, 'desk-ready')
    await a.keyboard.press('Control+Enter')
    await a.waitForSelector('.entries li')
    await shot(a, 'desk-borrowed')
    const loans = (await api.call('GET', `loans?studentId=${studentId}&status=ACTIVE`)).data.items
    must(loans.some((l) => l.accessionNo === borrowCopy.accessionNo), '后端没有对应借阅')
    return borrowCopy.accessionNo
  })

  await step(a, '流通台：归还', async () => {
    await a.click('[role=tab]:has-text("归还")')
    await a.fill('input[aria-label=归还登录号]', borrowCopy.accessionNo)
    await a.keyboard.press('Enter')
    await a.waitForSelector('.receipt')
    await shot(a, 'desk-returned')
    const loans = (await api.call('GET', `loans?studentId=${studentId}&status=RETURNED`)).data.items
    must(loans.some((l) => l.accessionNo === borrowCopy.accessionNo), '没有归还记录')
  })

  await step(a, '借书记录与还书记录可按学生查询', async () => {
    await a.goto(WEB + '/returns?q=' + studentNo)
    await a.waitForSelector('tbody tr')
    await shot(a, 'returns')
    const text = await a.textContent('tbody')
    must(text.includes(borrowCopy.accessionNo), '还书记录里没有刚才的登录号')
  })

  await step(a, '还书记录：撤销误归还（必须填原因），再到流通台归还', async () => {
    await a.locator('tr', { hasText: borrowCopy.accessionNo }).locator('button[aria-haspopup]').click()
    await a.click('[role=menuitem]:has-text("撤销误归还")')
    await a.fill('#reason-text', '端到端测试：模拟误扫归还')
    await a.click('button[form=reason-form]')
    await a.waitForTimeout(800)
    const back = (await api.call('GET', `loans?studentId=${studentId}&status=ACTIVE`)).data.items
    must(back.some((l) => l.accessionNo === borrowCopy.accessionNo), '撤销后借阅没有恢复为在借')
    await a.goto(WEB + '/desk')
    await a.click('[role=tab]:has-text("归还")')
    await a.fill('input[aria-label=归还登录号]', borrowCopy.accessionNo)
    await a.keyboard.press('Enter')
    await a.waitForSelector('.receipt')
    const active = (await api.call('GET', `loans?studentId=${studentId}&status=ACTIVE`)).data.items
    must(active.length === 0, '再次归还后仍有在借')
  })

  const importIsbns = [1, 2].map((i) => isbn13('979' + stamp.slice(8, 15).replace('T', '') + String(i).padStart(3, '0')))
  await step(a, '批量导入 CSV：预检 → 执行', async () => {
    const csv = ['title,authors,isbn13,publisher,publicationDate', ...importIsbns.map((isbn, i) => `E2E 导入测试 ${i + 1},测试作者,${isbn},测试出版社,2024-0${i + 1}`)].join('\n')
    const file = path.join(OUT, 'import.csv')
    fs.writeFileSync(file, csv)
    await a.goto(WEB + '/import')
    await a.locator('input[type=file]').setInputFiles(file)
    await a.click('button:has-text("预检")')
    await a.waitForSelector('text=预检：共 2 条')
    await a.click('button:has-text("执行导入")')
    await a.waitForSelector('text=已导入：新建 2')
    await shot(a, 'import')
    const found = []
    for (const isbn of importIsbns) {
      const r = (await api.call('GET', 'books?q=' + isbn + '&size=5')).data.items
      if (r.length) found.push(r[0].id)
    }
    must(found.length === 2, `只找到 ${found.length} 个导入的版本`)
    for (const id of found) {
      const cs = (await api.call('GET', `books/${id}/copies?size=10`)).data.items
      for (const c of cs) await api.call('DELETE', `copies/${c.id}`, { body: { reason: '端到端测试结束' } })
      await api.call('DELETE', `books/${id}`, { body: { reason: '端到端测试结束' } })
    }
    return `版本 #${found.join(', #')}（已清理）`
  })

  await step(a, '重复借出同一册被拒绝（后端校验）', async () => {
    const c1 = (await api.call('GET', 'search/books?q=' + encodeURIComponent('UNIX') + '&limit=5')).data.hits
    // 找一册已借出的副本，直接走 REST，确认后端拒绝而不是靠前端拦截
    let lent
    for (const h of c1) {
      lent = (await api.call('GET', `books/${h.book.id}/copies?size=100`)).data.items.find((c) => c.circulationStatus === 'BORROWED')
      if (lent) break
    }
    must(lent, '没有找到已借出的副本')
    const r = await api.call('POST', 'loans', { body: { studentId, accessionNos: [lent.accessionNo] }, headers: { 'Idempotency-Key': crypto.randomUUID() } })
    must(!r.ok, '后端竟然允许重复借出')
    return `${r.status} ${r.error?.code}`
  })

  await step(a, '条码标签生成', async () => {
    await a.goto(WEB + '/labels?')
    await a.fill('input[aria-label=搜索副本]', 'E2E 测试版本')
    await a.waitForTimeout(800)
    await a.locator('tbody .cb').first().click()
    await a.click('text=生成标签')
    await a.waitForSelector('.label img')
    await shot(a, 'labels', { fullPage: true })
  })

  let barcodeFile = null
  await step(a, '识书：上传馆藏条码图片', async () => {
    const res = await api.raw('GET', `copies/${borrowCopy.id}/barcode`, { accept: 'image/png' })
    must(res.ok, '条码图片下载失败 ' + res.status)
    barcodeFile = path.join(OUT, 'barcode-input.png')
    fs.writeFileSync(barcodeFile, Buffer.from(await res.arrayBuffer()))
    await a.goto(WEB + '/identify')
    await a.locator('input[type=file]').last().setInputFiles(barcodeFile)
    await a.waitForSelector(`text=${borrowCopy.accessionNo}`, { timeout: 15000 })
    await shot(a, 'identify-barcode')
    return borrowCopy.accessionNo
  })

  const noteTitle = `E2E 测试馆员笔记 ${stamp}`
  await step(a, '知识资料：新增、编辑、停用', async () => {
    await a.goto(WEB + '/knowledge')
    await a.click('text=新增资料')
    await a.fill('#kn-title', noteTitle)
    await a.selectOption('#kn-type', 'MANUAL_NOTE')
    await a.selectOption('#kn-vis', 'ADMIN')
    await a.fill('#kn-content', '这是端到端测试写入的馆员笔记，测试结束后停用。')
    await a.click('button[form=kn-form]')
    await a.waitForSelector(`text=${noteTitle}`)
    await a.locator('tr', { hasText: noteTitle }).locator('button[aria-haspopup]').click()
    await a.click('[role=menuitem]:has-text("编辑")')
    await a.fill('#kn-content', '这是端到端测试写入的馆员笔记（已编辑），测试结束后停用。')
    await a.click('button[form=kn-form]')
    await a.waitForTimeout(800)
    await a.locator('tr', { hasText: noteTitle }).locator('button[aria-haspopup]').click()
    await a.click('[role=menuitem]:has-text("停用")')
    await a.fill('#reason-text', '端到端测试结束')
    await a.click('button[form=reason-form]')
    await a.waitForTimeout(800)
    await shot(a, 'knowledge')
    const docs = (await api.call('GET', 'knowledge')).data
    const d = docs.find((x) => x.title === noteTitle)
    must(d && d.version >= 2 && !d.enabled, `资料状态不对：${JSON.stringify(d && { v: d.version, enabled: d.enabled })}`)
    return `v${d.version}，已停用`
  })

  await step(a, '统计、审计、AI 与索引页面可读', async () => {
    await a.goto(WEB + '/stats')
    await a.waitForSelector('svg')
    await shot(a, 'stats')
    await a.goto(WEB + '/audit')
    await a.waitForSelector('tbody tr')
    await a.locator('tbody tr').first().click()
    await a.waitForTimeout(500)
    await shot(a, 'audit')
    await a.keyboard.press('Escape')
    await a.goto(WEB + '/ai-ops')
    await a.waitForTimeout(1200)
    await shot(a, 'ai-ops')
  })

  // ------------------------------------------------------------------ 学生（手机）
  const sctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' })
  const s = await sctx.newPage()
  s.on('pageerror', (e) => consoleErrors.push('student: ' + e.message))
  let studentLoan = null

  await step(s, '学生登录后进入我的书架', async () => {
    await login(s, stuUser, stuPass)
    must(s.url().endsWith('/shelf'), s.url())
    await s.waitForTimeout(800)
    await shot(s, 'm-shelf-empty')
  })

  await step(s, '学生不能进入管理端页面', async () => {
    await s.goto(WEB + '/desk')
    await s.waitForTimeout(600)
    must(s.url().endsWith('/shelf'), '没有被送回书架：' + s.url())
  })

  await step(s, '检索 → 图书详情 → 选择具体一册 → 确认卡 → 点击确认借出', async () => {
    await s.goto(WEB + '/search?q=' + encodeURIComponent('设计'))
    await s.waitForSelector('a[href^="/book/"]')
    await shot(s, 'm-search')
    const bookUrl = `/book/${borrowCopy.editionId}`
    await s.goto(WEB + bookUrl)
    await s.waitForSelector('text=借这一册')
    await s.locator('text=借这一册').first().click()
    await s.waitForSelector('[aria-label$="确认卡"]')
    await shot(s, 'm-borrow-card')
    await s.locator('[aria-label$="确认卡"] .acts .btn-primary').click()
    await s.waitForSelector('text=已完成')
    await shot(s, 'm-borrow-done')
    const me = await sctxApi('GET', 'me/loans')
    studentLoan = me.data.items.find((l) => l.status === 'ACTIVE')
    must(studentLoan, '书架上没有借阅')
    return `${studentLoan.accessionNo}《${studentLoan.title}》`
  })

  async function sctxApi(method, p) {
    // 用学生浏览器里的 Cookie 直接调接口（只读），核对页面与后端一致
    return s.evaluate(async ([m, url]) => {
      const r = await fetch('/api/v1/' + url, { method: m, headers: { Accept: 'application/json' } })
      const j = await r.json().catch(() => null)
      return { status: r.status, ok: r.ok, data: j?.data, error: j }
    }, [method, p])
  }

  await step(s, '书架续借：生成确认卡后取消，借阅不变', async () => {
    await s.goto(WEB + '/shelf')
    await s.waitForSelector('.loan')
    await s.locator('.loan button:has-text("续借")').first().click()
    await s.waitForSelector('[aria-label$="确认卡"]')
    await shot(s, 'm-renew-card')
    await s.locator('[aria-label$="确认卡"] .acts .btn-quiet').click()
    await s.waitForSelector('text=已取消')
    const me = (await sctxApi('GET', 'me/loans')).data.items.find((l) => l.id === studentLoan.id)
    must(me.renewCount === 0 && me.dueAt === studentLoan.dueAt, '取消后借阅被改变')
    await s.keyboard.press('Escape')
  })

  await step(s, '借阅历史', async () => {
    await s.goto(WEB + '/history')
    await s.waitForSelector('.list li')
    await shot(s, 'm-history')
  })

  await step(s, '识书：学生上传条码图片', async () => {
    await s.goto(WEB + '/scan')
    await s.locator('input[type=file]').last().setInputFiles(barcodeFile)
    await s.waitForSelector(`text=${borrowCopy.accessionNo}`, { timeout: 15000 })
    await shot(s, 'm-scan')
  })

  await step(s, '越权：学生调用管理员接口与他人借阅', async () => {
    const r1 = await sctxApi('GET', 'students?page=1&size=5')
    const r2 = await sctxApi('GET', 'audit-logs?page=1&size=5')
    const other = (await api.call('GET', 'loans?status=ACTIVE&size=5')).data.items.find((l) => l.studentId !== studentId)
    const r3 = await sctxApi('GET', `loans/${other.id}`)
    const r4 = await s.evaluate(async () => (await fetch('/api/v1/me/loans', { method: 'GET' })).status)
    must(r1.status === 403 && r2.status === 403, `管理员接口：${r1.status}/${r2.status}`)
    must(r3.status === 404 || r3.status === 403, `他人借阅：${r3.status}`)
    // 写请求不带 CSRF 头
    const r5 = await s.evaluate(async (id) => {
      const r = await fetch('/api/v1/ai/actions/prepare-renew', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify({ loanId: id }) })
      return { status: r.status, code: (await r.json().catch(() => ({}))).code }
    }, studentLoan.id)
    must(r5.status === 403 && r5.code === 'CSRF_TOKEN_INVALID', `无 CSRF：${JSON.stringify(r5)}`)
    return `students ${r1.status} · audit ${r2.status} · 他人借阅 ${r3.status} · 本人 ${r4} · 无CSRF ${r5.status} ${r5.code}`
  })

  if (AI !== 'off') {
    await step(s, `AI 助手：流式检索回答（${AI}）`, async () => {
      await s.goto(WEB + '/assistant')
      await markMock(s)
      await s.fill('textarea', '有没有讲 UNIX 系统编程的书？')
      await s.keyboard.press('Enter')
      await s.waitForSelector('.draft .caret', { timeout: 20000 }).catch(() => {})
      await shot(s, 'm-ai-streaming')
      await s.waitForSelector('.a-msg.st-done', { timeout: 60000 })
      await s.waitForTimeout(400)
      await shot(s, 'm-ai-answer')
      const refs = await s.locator('.a-msg.st-done .ref').count()
      return `${refs} 个版本引用`
    })

    if (AI !== 'replay') {
      await step(s, 'AI 助手：续借确认卡 → 刷新恢复 → 点击确认', async () => {
        await s.goto(WEB + '/assistant')
        await markMock(s)
        await s.fill('textarea', '帮我续借第一本书')
        await s.keyboard.press('Enter')
        await s.waitForSelector('.a-msg [aria-label$="确认卡"]', { timeout: 60000 })
        await shot(s, 'm-ai-renew-card')
        await s.reload()
        await markMock(s)
        await s.waitForSelector('[aria-label$="确认卡"] .acts .btn-primary', { timeout: 15000 })
        await shot(s, 'm-ai-card-recovered')
        await s.locator('[aria-label$="确认卡"] .acts .btn-primary').first().click()
        await s.waitForSelector('text=已完成')
        await shot(s, 'm-ai-renew-done')
        const me = (await sctxApi('GET', 'me/loans')).data.items.find((l) => l.id === studentLoan.id)
        must(me.renewCount === 1, 'renewCount 应为 1，实际 ' + me.renewCount)
        return `应还 ${studentLoan.dueAt.slice(0, 10)} → ${me.dueAt.slice(0, 10)}`
      })
    }

    if (AI === 'mock') {
      await step(s, 'AI 助手：停止生成后标明本轮不完整', async () => {
        await s.goto(WEB + '/assistant')
        await markMock(s)
        await s.fill('textarea', '请给我一个长回答')
        await s.keyboard.press('Enter')
        await s.waitForFunction(() => (document.querySelector('.draft')?.textContent ?? '').length > 60, null, { timeout: 30000 })
        await s.click('button[aria-label=停止生成]')
        await s.waitForSelector('.a-msg.st-stopped')
        await shot(s, 'm-ai-stopped')
      })
      await step(s, 'AI 助手：模型中途失败显示错误与请求号', async () => {
        await s.goto(WEB + '/assistant')
        await markMock(s)
        await s.fill('textarea', '模拟故障：查一下有什么书')
        await s.keyboard.press('Enter')
        await s.waitForSelector('.a-msg.st-error', { timeout: 60000 })
        await shot(s, 'm-ai-error')
        return (await s.textContent('.a-msg.st-error .msg-error')).replace(/\s+/g, ' ').slice(0, 80)
      })
    }
  }

  // ------------------------------------------------------------------ 收尾
  await step(a, '收尾：归还测试借阅、停用测试学生、归档测试版本', async () => {
    const active = (await api.call('GET', `loans?studentId=${studentId}&status=ACTIVE`)).data.items
    await a.goto(WEB + '/desk')
    await a.click('[role=tab]:has-text("归还")')
    for (const l of active) {
      await a.fill('input[aria-label=归还登录号]', l.accessionNo)
      await a.keyboard.press('Enter')
      await a.waitForSelector(`.receipt >> text=${l.accessionNo}`)
    }
    const del = await api.call('DELETE', `students/${studentId}`, { body: { reason: '端到端测试结束' } })
    const copies = (await api.call('GET', `books/${testEdition}/copies?size=10`)).data.items
    for (const c of copies) await api.call('DELETE', `copies/${c.id}`, { body: { reason: '端到端测试结束' } })
    const arc = await api.call('DELETE', `books/${testEdition}`, { body: { reason: '端到端测试结束' } })
    return `归还 ${active.length} 册；学生 ${del.data}；版本 ${arc.data ?? arc.error?.code}`
  })

  await browser.close()

  const pass = results.filter((r) => r.ok).length
  const lines = [
    `# LibraMind 端到端流程记录 ${stamp}`,
    '',
    `- 前端：${WEB}；模型侧：**${AI}**${AI === 'mock' ? '（本地模拟服务，不是真实模型）' : ''}`,
    `- 结果：${pass}/${results.length} 通过`,
    '',
    '| # | 步骤 | 结果 | 说明 | 耗时 |',
    '|---|---|---|---|---|',
    ...results.map((r, i) => `| ${i + 1} | ${r.name} | ${r.ok ? '通过' : '**失败**'} | ${String(r.detail).replace(/\|/g, '\\|')} | ${r.ms} ms |`),
    '',
    consoleErrors.length ? '## 页面脚本错误\n\n' + consoleErrors.map((e) => '- ' + e).join('\n') : '页面没有未捕获的脚本错误。',
    '',
  ]
  fs.writeFileSync(path.join(OUT, 'report.md'), lines.join('\n'))
  console.log(`\n${pass}/${results.length} 通过，记录在 ${OUT}`)
  process.exitCode = pass === results.length ? 0 : 1
})().catch((e) => {
  console.error(e)
  process.exit(1)
})
