<script setup lang="ts">
// 流通台：借出（学号 → 资格 → 逐册登录号 → 预检 → 原子提交）与归还（登录号直接归还）。
// 每次提交是一个 Intent：网络不明时用同一幂等键和同一请求体重试，修改内容就作废旧 Intent。
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import {
  ArrowUpRight, ArrowDownLeft, X, CornerDownLeft, UserRound, CircleAlert, LoaderCircle, RotateCw, Undo2,
} from 'lucide-vue-next'
import { admin, loans as loanApi, returns as returnApi, students as studentApi } from '@/api/endpoints'
import { ApiError, Intent, errorMessage } from '@/api/http'
import type { CopyCheck, Eligibility, LoanView, Overview, StudentView } from '@/api/types'
import { copyStatus, dueInfo, errorHints, fmtDate, fmtTime } from '@/lib/format'
import { toast } from '@/stores/toast'

const route = useRoute()
type Mode = 'borrow' | 'return'
const mode = ref<Mode>('borrow')

// ---------- 概况（只显示需要处理的数字） ----------
const overview = ref<Overview | null>(null)
async function loadOverview() {
  try {
    overview.value = await admin.overview()
  } catch {
    /* 概况失败不影响流通 */
  }
}

// ---------- 读者 ----------
const studentNo = ref('')
const studentInput = ref<HTMLInputElement>()
const student = ref<StudentView | null>(null)
const eligibility = ref<Eligibility | null>(null)
const activeLoans = ref<LoanView[]>([])
const studentError = ref('')
const studentBusy = ref(false)

async function lookupStudent() {
  const no = studentNo.value.trim()
  if (!no) return
  studentBusy.value = true
  studentError.value = ''
  try {
    const s = await studentApi.byNumber(no)
    const [e, l] = await Promise.all([
      studentApi.eligibility(s.id),
      studentApi.loans(s.id, { status: 'ACTIVE', size: 20 }),
    ])
    student.value = s
    eligibility.value = e
    activeLoans.value = l.items.filter((x) => x.status === 'ACTIVE')
    batch.splice(0)
    pendingIntent.value = null
    await nextTick()
    accessionInput.value?.focus()
  } catch (e) {
    studentError.value = e instanceof ApiError && e.status === 404 ? `没有学号为 ${no} 的学生` : errorMessage(e)
  } finally {
    studentBusy.value = false
  }
}

function clearStudent() {
  student.value = null
  eligibility.value = null
  activeLoans.value = []
  batch.splice(0)
  pendingIntent.value = null
  studentNo.value = ''
  borrowProblem.value = null
  nextTick(() => studentInput.value?.focus())
}

// ---------- 本次借出的册 ----------
interface Row {
  accessionNo: string
  check: CopyCheck | null
}
const batch = reactive<Row[]>([])
const accessionNo = ref('')
const accessionInput = ref<HTMLInputElement>()
const addError = ref('')
const adding = ref(false)
const note = ref('')
const pendingIntent = ref<Intent<{ loans: LoanView[] }> | null>(null)
const borrowProblem = ref<{ message: string; uncertain: boolean } | null>(null)
const submitting = ref(false)

function reasonOf(check: CopyCheck | null): string {
  if (!check) return '核对中'
  if (check.available) return ''
  if (!check.copy) return '登录号不存在'
  if (check.copy.archived) return '已注销'
  const s = check.copy.circulationStatus
  if (s !== 'AVAILABLE') return copyStatus[s].label
  return check.reason || '不可借'
}

async function runPreview(nos: string[]) {
  if (!student.value) return null
  return loanApi.preview({ studentId: student.value.id, accessionNos: nos })
}

async function addAccession() {
  const no = accessionNo.value.trim().toUpperCase()
  if (!no || !student.value) return
  addError.value = ''
  if (batch.some((r) => r.accessionNo === no)) {
    addError.value = `${no} 已在本次清单中`
    accessionNo.value = ''
    return
  }
  if (batch.length >= 10) {
    addError.value = '一次最多借出 10 册'
    return
  }
  adding.value = true
  try {
    const preview = await runPreview([...batch.map((r) => r.accessionNo), no])
    if (!preview) return
    const check = preview.copies.find((c) => c.accessionNo === no) ?? null
    if (!check?.copy) {
      addError.value = `找不到登录号 ${no}`
      return
    }
    batch.push({ accessionNo: no, check })
    applyPreview(preview.copies, preview.eligibility)
    accessionNo.value = ''
    pendingIntent.value = null
    borrowProblem.value = null
  } catch (e) {
    addError.value = errorMessage(e)
  } finally {
    adding.value = false
    accessionInput.value?.focus()
  }
}

function applyPreview(checks: CopyCheck[], e: Eligibility) {
  for (const row of batch) row.check = checks.find((c) => c.accessionNo === row.accessionNo) ?? row.check
  eligibility.value = e
}

async function removeRow(i: number) {
  batch.splice(i, 1)
  pendingIntent.value = null
  borrowProblem.value = null
  if (batch.length) {
    const p = await runPreview(batch.map((r) => r.accessionNo)).catch(() => null)
    if (p) applyPreview(p.copies, p.eligibility)
  }
  accessionInput.value?.focus()
}

const blockedRows = computed(() => batch.filter((r) => !r.check?.available).length)
const overLimit = computed(() => (eligibility.value ? batch.length > eligibility.value.remaining : false))
const canSubmit = computed(
  () =>
    !!student.value &&
    batch.length > 0 &&
    blockedRows.value === 0 &&
    !overLimit.value &&
    !!eligibility.value?.canBorrow &&
    !submitting.value,
)

async function submitBorrow() {
  if (!canSubmit.value && !pendingIntent.value) return
  submitting.value = true
  borrowProblem.value = null
  // 首次提交创建 Intent；“结果不明”后的重试沿用它。
  const intent =
    pendingIntent.value ??
    loanApi.borrowIntent({
      studentId: student.value!.id,
      accessionNos: batch.map((r) => r.accessionNo),
      note: note.value.trim() || undefined,
    })
  pendingIntent.value = intent
  try {
    const result = await intent.send()
    pendingIntent.value = null
    log.unshift({ kind: 'borrow', at: new Date().toISOString(), studentName: student.value!.name, studentNo: student.value!.studentNo, loans: result.loans })
    toast('ok', `已为 ${student.value!.name} 借出 ${result.loans.length} 册`, `应还 ${fmtDate(result.loans[0]?.dueAt)}`)
    batch.splice(0)
    note.value = ''
    await lookupStudentRefresh()
    loadOverview()
  } catch (e) {
    const err = e as ApiError
    if (err.uncertain) {
      borrowProblem.value = { message: '网络中断，无法确认是否已借出。请点“按原请求重试”，不会重复借出。', uncertain: true }
    } else {
      pendingIntent.value = null
      borrowProblem.value = { message: errorHints[err.code] ? `${errorHints[err.code]}（${err.message}）` : errorMessage(e), uncertain: false }
      const p = await runPreview(batch.map((r) => r.accessionNo)).catch(() => null)
      if (p) applyPreview(p.copies, p.eligibility)
    }
  } finally {
    submitting.value = false
  }
}

async function lookupStudentRefresh() {
  if (!student.value) return
  const id = student.value.id
  const [s, e, l] = await Promise.all([
    studentApi.get(id),
    studentApi.eligibility(id),
    studentApi.loans(id, { status: 'ACTIVE', size: 20 }),
  ])
  student.value = s
  eligibility.value = e
  activeLoans.value = l.items.filter((x) => x.status === 'ACTIVE')
}

// ---------- 归还 ----------
const returnNo = ref('')
const returnInput = ref<HTMLInputElement>()
const returnBusy = ref(false)
const returnProblem = ref<{ message: string; uncertain: boolean; no: string } | null>(null)
const returnIntent = ref<Intent<LoanView> | null>(null)
const lastReturn = ref<LoanView | null>(null)

async function submitReturn(retry = false) {
  const no = retry ? returnProblem.value?.no ?? '' : returnNo.value.trim().toUpperCase()
  if (!no) return
  returnBusy.value = true
  const intent = retry && returnIntent.value ? returnIntent.value : returnApi.returnIntent({ accessionNo: no })
  returnIntent.value = intent
  returnProblem.value = null
  try {
    const loan = await intent.send()
    returnIntent.value = null
    lastReturn.value = loan
    log.unshift({ kind: 'return', at: new Date().toISOString(), studentName: loan.studentName, studentNo: loan.studentNo, loans: [loan] })
    returnNo.value = ''
    loadOverview()
  } catch (e) {
    const err = e as ApiError
    if (err.uncertain) {
      returnProblem.value = { message: '网络中断，无法确认是否已归还。', uncertain: true, no }
    } else {
      returnIntent.value = null
      const msg =
        err.status === 404 || err.code === 'LOAN_NOT_ACTIVE'
          ? `${no} 没有在借记录，可能已归还或登录号有误`
          : errorHints[err.code] ?? errorMessage(e)
      returnProblem.value = { message: msg, uncertain: false, no }
    }
  } finally {
    returnBusy.value = false
    returnInput.value?.focus()
  }
}

// ---------- 本班次记录 ----------
interface LogEntry {
  kind: 'borrow' | 'return'
  at: string
  studentName: string
  studentNo: string
  loans: LoanView[]
}
const log = reactive<LogEntry[]>([])

// ---------- 键盘 ----------
function setMode(m: Mode) {
  mode.value = m
  nextTick(() => {
    if (m === 'return') returnInput.value?.focus()
    else if (student.value) accessionInput.value?.focus()
    else studentInput.value?.focus()
  })
}
function onKey(e: KeyboardEvent) {
  if (e.key === 'F2') {
    e.preventDefault()
    setMode('borrow')
  } else if (e.key === 'F4') {
    e.preventDefault()
    setMode('return')
  } else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && mode.value === 'borrow') {
    e.preventDefault()
    submitBorrow()
  } else if (e.key === 'Escape' && mode.value === 'borrow' && student.value && !document.querySelector('dialog[open]')) {
    if (accessionNo.value) accessionNo.value = ''
    else if (!batch.length) clearStudent()
  }
}
onMounted(() => {
  window.addEventListener('keydown', onKey)
  loadOverview()
  studentInput.value?.focus()
  // 从学生详情跳转过来时直接带出读者。
  if (typeof route.query.student === 'string' && route.query.student) {
    studentNo.value = route.query.student
    lookupStudent()
  }
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

const elig = computed(() => {
  const e = eligibility.value
  if (!e) return null
  const used = e.currentBorrowedCount + batch.length
  return { used, limit: e.limit, pct: Math.min(100, (used / Math.max(1, e.limit)) * 100) }
})
</script>

<template>
  <div class="page desk">
    <header class="page-head">
      <div>
        <h1 class="page-title">流通台</h1>
        <p class="page-sub">扫码或输入后按回车。借出必须逐册登记馆藏登录号。</p>
      </div>
      <div class="head-right">
        <p v-if="overview" class="pulse">
          <RouterLink to="/loans?status=ACTIVE">在借 <b class="num">{{ overview.activeLoans }}</b></RouterLink>
          <span class="sep">·</span>
          <RouterLink to="/loans?status=ACTIVE" :class="{ hot: overview.overdueLoans > 0 }" title="逾期记录在借书记录中以红色标出">
            逾期 <b class="num">{{ overview.overdueLoans }}</b>
          </RouterLink>
        </p>
        <div class="seg" role="tablist" aria-label="流通模式">
          <button role="tab" type="button" :aria-selected="mode === 'borrow'" :class="{ on: mode === 'borrow' }" @click="setMode('borrow')">
            <ArrowUpRight />借出<span class="kbd">F2</span>
          </button>
          <button role="tab" type="button" :aria-selected="mode === 'return'" :class="{ on: mode === 'return' }" @click="setMode('return')">
            <ArrowDownLeft />归还<span class="kbd">F4</span>
          </button>
        </div>
      </div>
    </header>

    <div class="grid">
      <!-- ============ 借出 ============ -->
      <section v-if="mode === 'borrow'" class="work" aria-label="借出">
        <div class="step" :class="{ done: !!student }">
          <div class="step-label"><span class="n">1</span>读者</div>
          <form v-if="!student" class="scan" @submit.prevent="lookupStudent">
            <input
              ref="studentInput"
              v-model="studentNo"
              class="input input-lg mono"
              inputmode="numeric"
              placeholder="输入或扫描学号"
              aria-label="学号"
              autocomplete="off"
            />
            <button class="btn btn-lg" type="submit" :disabled="studentBusy || !studentNo.trim()">
              <LoaderCircle v-if="studentBusy" class="spin" /><CornerDownLeft v-else />查找
            </button>
          </form>
          <p v-if="studentError" class="inline-err" role="alert"><CircleAlert />{{ studentError }}</p>

          <div v-if="student" class="reader">
            <div class="reader-main">
              <span class="avatar"><UserRound /></span>
              <div class="who">
                <p class="rn">{{ student.name }} <span class="mono muted">{{ student.studentNo }}</span></p>
                <p class="muted">{{ student.college }} · {{ student.major }}</p>
              </div>
              <span v-if="!student.enabled" class="badge danger">已停用</span>
              <button class="btn btn-sm btn-quiet change" type="button" @click="clearStudent">更换读者 <span class="kbd">Esc</span></button>
            </div>
            <div v-if="elig && eligibility" class="quota">
              <div class="bar" :class="{ full: overLimit }"><span :style="{ width: elig.pct + '%' }"></span></div>
              <p class="num">
                已借 {{ eligibility.currentBorrowedCount }}
                <template v-if="batch.length"> + 本次 {{ batch.length }}</template>
                / 上限 {{ eligibility.limit }}
                <span v-if="eligibility.overdueCount" class="warn-text"> · 有 {{ eligibility.overdueCount }} 册逾期</span>
              </p>
            </div>
            <p v-if="eligibility && !eligibility.canBorrow" class="inline-err"><CircleAlert />当前不能借书{{ !eligibility.enabled ? '：学生已停用' : eligibility.remaining <= 0 ? '：已达在借上限' : '' }}</p>
            <ul v-if="activeLoans.length" class="current">
              <li v-for="l in activeLoans" :key="l.id">
                <span class="t serif truncate">{{ l.title }}</span>
                <span class="mono muted">{{ l.accessionNo }}</span>
                <span class="badge" :class="dueInfo(l.dueAt, l.overdue).tone">{{ dueInfo(l.dueAt, l.overdue).label }}</span>
              </li>
            </ul>
          </div>
        </div>

        <div class="step" :class="{ disabled: !student }">
          <div class="step-label"><span class="n">2</span>馆藏登录号</div>
          <form class="scan" @submit.prevent="addAccession">
            <input
              ref="accessionInput"
              v-model="accessionNo"
              class="input input-lg mono"
              placeholder="例如 LM-9787115147318-01"
              aria-label="馆藏登录号"
              autocomplete="off"
              :disabled="!student || submitting"
            />
            <button class="btn btn-lg" type="submit" :disabled="!student || adding || !accessionNo.trim()">
              <LoaderCircle v-if="adding" class="spin" /><CornerDownLeft v-else />加入
            </button>
          </form>
          <p v-if="addError" class="inline-err" role="alert"><CircleAlert />{{ addError }}</p>
          <p v-else-if="student" class="hint">ISBN 只能确定版本，不能确定是哪一册；请扫描书后的登录号条码。</p>

          <table v-if="batch.length" class="table batch">
            <thead>
              <tr><th>书名</th><th>登录号</th><th>位置</th><th>核对</th><th class="col-actions"><span class="sr-only">操作</span></th></tr>
            </thead>
            <tbody>
              <tr v-for="(row, i) in batch" :key="row.accessionNo" :class="{ bad: !row.check?.available }">
                <td>
                  <p class="serif bt">{{ row.check?.copy?.title }}</p>
                  <p class="muted small truncate">{{ row.check?.copy?.authors }}</p>
                </td>
                <td class="mono nowrap">{{ row.accessionNo }}</td>
                <td class="small">{{ row.check?.copy?.location || '—' }}</td>
                <td>
                  <span v-if="row.check?.available" class="badge ok">可借</span>
                  <span v-else class="badge danger">{{ reasonOf(row.check) }}</span>
                </td>
                <td class="col-actions">
                  <button class="btn btn-sm btn-quiet btn-icon" type="button" :aria-label="`移除 ${row.accessionNo}`" @click="removeRow(i)"><X /></button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="student" class="commit">
          <input v-model="note" class="input" placeholder="备注（可选）" aria-label="借阅备注" maxlength="200" />
          <div v-if="borrowProblem" class="problem" :class="{ uncertain: borrowProblem.uncertain }" role="alert">
            <CircleAlert />
            <span>{{ borrowProblem.message }}</span>
          </div>
          <div class="commit-row">
            <p class="muted small">
              <template v-if="blockedRows">有 {{ blockedRows }} 册不可借，请移除后再提交。</template>
              <template v-else-if="overLimit">超出可借数量 {{ batch.length - (eligibility?.remaining ?? 0) }} 册。</template>
              <template v-else-if="batch.length">全部 {{ batch.length }} 册一起成功或一起失败，应还日期由系统按借阅规则计算。</template>
              <template v-else>先扫描至少一册。</template>
            </p>
            <button
              v-if="borrowProblem?.uncertain"
              class="btn btn-primary btn-lg"
              type="button"
              :disabled="submitting"
              @click="submitBorrow"
            >
              <RotateCw />按原请求重试
            </button>
            <button v-else class="btn btn-primary btn-lg" type="button" :disabled="!canSubmit" @click="submitBorrow">
              <LoaderCircle v-if="submitting" class="spin" />{{ batch.length ? `借出 ${batch.length} 册` : '借出' }}
              <span class="kbd on-accent">Ctrl ↵</span>
            </button>
          </div>
        </div>
      </section>

      <!-- ============ 归还 ============ -->
      <section v-else class="work" aria-label="归还">
        <div class="step">
          <div class="step-label"><span class="n"><Undo2 /></span>扫描要归还的册</div>
          <form class="scan" @submit.prevent="submitReturn()">
            <input
              ref="returnInput"
              v-model="returnNo"
              class="input input-lg mono"
              placeholder="馆藏登录号，回车即归还"
              aria-label="归还登录号"
              autocomplete="off"
              autofocus
            />
            <button class="btn btn-primary btn-lg" type="submit" :disabled="returnBusy || !returnNo.trim()">
              <LoaderCircle v-if="returnBusy" class="spin" /><CornerDownLeft v-else />归还
            </button>
          </form>
          <div v-if="returnProblem" class="problem" :class="{ uncertain: returnProblem.uncertain }" role="alert">
            <CircleAlert />
            <span>{{ returnProblem.message }}</span>
            <button v-if="returnProblem.uncertain" class="btn btn-sm" type="button" @click="submitReturn(true)"><RotateCw />按原请求重试</button>
          </div>
          <p v-else class="hint">系统按登录号找到在借记录并归还；不能新增没有借阅来源的还书记录。</p>

          <div v-if="lastReturn" class="receipt" aria-live="polite">
            <p class="section-title">刚刚归还</p>
            <p class="serif rt">{{ lastReturn.title }}</p>
            <dl class="dl">
              <dt>登录号</dt><dd class="mono">{{ lastReturn.accessionNo }}</dd>
              <dt>读者</dt><dd>{{ lastReturn.studentName }} <span class="mono muted">{{ lastReturn.studentNo }}</span></dd>
              <dt>借出</dt><dd class="num">{{ fmtDate(lastReturn.borrowedAt) }}</dd>
              <dt>应还</dt><dd class="num">{{ fmtDate(lastReturn.dueAt) }} <span v-if="lastReturn.overdue" class="badge danger">逾期归还</span></dd>
            </dl>
          </div>
        </div>
      </section>

      <!-- ============ 本班次 ============ -->
      <aside class="log" aria-label="本次登录的流通记录">
        <p class="section-title">本次操作</p>
        <ol v-if="log.length" class="entries">
          <li v-for="(entry, i) in log" :key="i">
            <span class="ic" :class="entry.kind"><ArrowUpRight v-if="entry.kind === 'borrow'" /><ArrowDownLeft v-else /></span>
            <div class="eb">
              <p>
                <b>{{ entry.kind === 'borrow' ? '借出' : '归还' }}</b>
                {{ entry.studentName }}
                <span class="muted num">· {{ fmtTime(entry.at) }}</span>
              </p>
              <p v-for="l in entry.loans" :key="l.id" class="muted small truncate">
                {{ l.title }}<template v-if="entry.kind === 'borrow'"> · 应还 {{ fmtDate(l.dueAt) }}</template>
              </p>
            </div>
          </li>
        </ol>
        <p v-else class="muted small empty-log">本次登录后办理的借还会列在这里，刷新页面后清空；完整记录见借书记录与还书记录。</p>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.head-right {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
}
.pulse {
  font-size: var(--t-sm);
  color: var(--ink-3);
}
.pulse a {
  color: var(--ink-2);
}
.pulse b {
  color: var(--ink);
  font-weight: 600;
}
.pulse a.hot,
.pulse a.hot b {
  color: var(--danger);
}
.sep {
  margin: 0 8px;
}
.seg {
  display: inline-flex;
  padding: 3px;
  border-radius: var(--r-3);
  background: var(--paper-2);
  border: 1px solid var(--rule);
}
.seg button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 10px 0 12px;
  border: 0;
  border-radius: 7px;
  background: none;
  color: var(--ink-2);
  font-weight: 500;
  cursor: pointer;
}
.seg button svg {
  width: 16px;
  height: 16px;
}
.seg button.on {
  background: var(--surface);
  color: var(--ink);
  box-shadow: var(--shadow-1);
}
.seg .kbd {
  margin-left: 2px;
}
.grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 24px;
  align-items: start;
}
.work {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.step {
  padding: 18px 20px 20px;
  border: 1px solid var(--rule);
  border-radius: var(--r-4);
  background: var(--surface);
}
.step.disabled {
  opacity: 0.55;
}
.step-label {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  font-weight: 600;
}
.n {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--ink);
  color: var(--paper);
  font-size: 12px;
}
.n svg {
  width: 13px;
  height: 13px;
}
.step.done .n {
  background: var(--accent);
}
.scan {
  display: flex;
  gap: 8px;
}
.scan .input {
  flex: 1;
  letter-spacing: 0.02em;
}
.hint {
  margin-top: 8px;
  font-size: var(--t-sm);
  color: var(--ink-3);
}
.inline-err {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  color: var(--danger);
  font-size: var(--t-sm);
}
.inline-err svg {
  width: 15px;
  height: 15px;
}
.reader-main {
  display: flex;
  align-items: center;
  gap: 12px;
}
.avatar {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent-text);
}
.avatar svg {
  width: 20px;
  height: 20px;
}
.who {
  min-width: 0;
}
.rn {
  font-size: var(--t-xl);
  font-weight: 600;
}
.rn .mono {
  font-size: var(--t-md);
  font-weight: 400;
  margin-left: 6px;
}
.change {
  margin-left: auto;
}
.quota {
  margin-top: 14px;
  font-size: var(--t-sm);
  color: var(--ink-2);
}
.bar {
  height: 6px;
  border-radius: 3px;
  background: var(--paper-2);
  overflow: hidden;
  margin-bottom: 6px;
}
.bar span {
  display: block;
  height: 100%;
  background: var(--accent);
  border-radius: 3px;
  transition: width 0.2s var(--ease);
}
.bar.full span {
  background: var(--danger);
}
.warn-text {
  color: var(--danger);
}
.current {
  list-style: none;
  margin: 14px 0 0;
  padding: 10px 0 0;
  border-top: 1px dashed var(--rule-strong);
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: var(--t-sm);
}
.current li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 12px;
  align-items: center;
}
.batch {
  margin-top: 14px;
  border: 1px solid var(--rule);
  border-radius: var(--r-3);
  overflow: hidden;
}
.batch tr.bad td {
  background: color-mix(in srgb, var(--danger-soft) 55%, transparent);
}
.bt {
  font-size: var(--t-md);
  line-height: 1.4;
}
.small {
  font-size: var(--t-sm);
}
.commit {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 20px;
  border: 1px solid var(--rule);
  border-radius: var(--r-4);
  background: var(--surface-sunk);
}
.commit-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.kbd.on-accent {
  background: transparent;
  color: inherit;
  border-color: color-mix(in srgb, currentColor 40%, transparent);
  opacity: 0.8;
}
.problem {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--r-2);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: var(--t-sm);
}
.problem.uncertain {
  background: var(--warn-soft);
  color: var(--warn);
}
.problem svg {
  width: 16px;
  height: 16px;
  flex: none;
}
.problem .btn {
  margin-left: auto;
}
.receipt {
  margin-top: 18px;
  padding: 16px 18px;
  border-radius: var(--r-3);
  background: var(--ok-soft);
}
.rt {
  font-size: var(--t-xl);
  margin: 6px 0 10px;
}
.log {
  position: sticky;
  top: 20px;
  padding: 4px 0 0;
}
.entries {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}
.entries li {
  display: flex;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid var(--rule);
}
.ic {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  flex: none;
  border-radius: 50%;
}
.ic svg {
  width: 14px;
  height: 14px;
}
.ic.borrow {
  background: var(--warn-soft);
  color: var(--warn);
}
.ic.return {
  background: var(--ok-soft);
  color: var(--ok);
}
.eb {
  min-width: 0;
  font-size: var(--t-sm);
}
.empty-log {
  margin-top: 10px;
  line-height: 1.7;
}
.spin {
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 1100px) {
  .grid {
    grid-template-columns: 1fr;
  }
  .log {
    position: static;
  }
}
@media (max-width: 640px) {
  .commit-row {
    flex-direction: column;
    align-items: stretch;
  }
  .current li {
    grid-template-columns: 1fr auto;
  }
  .current li .mono {
    display: none;
  }
  .batch th:nth-child(3),
  .batch td:nth-child(3) {
    display: none;
  }
}
</style>
