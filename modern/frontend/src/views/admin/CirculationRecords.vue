<script setup lang="ts">
// 借书记录与还书记录共用的列表。还书记录只含 RETURNED，按归还日期过滤。
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search, ScrollText, RefreshCw, Undo2, PencilLine, Ban, ArrowDownLeft } from 'lucide-vue-next'
import { loans as loanApi, returns as returnApi } from '@/api/endpoints'
import { ApiError, errorMessage, type Intent } from '@/api/http'
import { errorHints } from '@/lib/format'
import { toast } from '@/stores/toast'
import RowMenu, { type MenuItem } from '@/components/RowMenu.vue'
import ReasonDialog from '@/components/ReasonDialog.vue'
import CorrectionDialog from './CorrectionDialog.vue'
import type { LoanView, Page } from '@/api/types'
import { dueInfo, fmtDate, loanStatus } from '@/lib/format'
import Pager from '@/components/Pager.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'

const props = defineProps<{ kind: 'loans' | 'returns' }>()
const route = useRoute()
const router = useRouter()

const q = ref(String(route.query.q ?? ''))
const status = ref(String(route.query.status ?? ''))
const from = ref(String(route.query.from ?? ''))
const to = ref(String(route.query.to ?? ''))
const page = ref(Number(route.query.page ?? 1) || 1)
const size = 25
const data = ref<Page<LoanView> | null>(null)
const loading = ref(false)
const error = ref<unknown>(null)

const isLoans = computed(() => props.kind === 'loans')

function iso(d: string, end = false) {
  if (!d) return undefined
  const date = new Date(`${d}T${end ? '23:59:59.999' : '00:00:00'}`)
  return date.toISOString()
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const query = { q: q.value.trim(), status: status.value || undefined, from: iso(from.value), to: iso(to.value, true), page: page.value, size }
    data.value = await (isLoans.value ? loanApi.list(query) : returnApi.list(query))
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}
function sync() {
  router.replace({ query: Object.fromEntries(Object.entries({ q: q.value, status: status.value, from: from.value, to: to.value, page: page.value > 1 ? String(page.value) : '' }).filter(([, v]) => v)) })
}
let t = 0
watch(q, () => {
  clearTimeout(t)
  t = window.setTimeout(() => { page.value = 1; sync(); load() }, 280)
})
watch([status, from, to], () => { page.value = 1; sync(); load() })
function go(p: number) { page.value = p; sync(); load() }
onMounted(load)

// ---------- 行操作 ----------
const correcting = ref<LoanView | null>(null)
const voiding = ref<LoanView | null>(null)

/** 网络结果不明时用同一个幂等键重发一次；仍不明就提示刷新核对。 */
async function sendOnce<T>(intent: Intent<T>): Promise<T> {
  try {
    return await intent.send()
  } catch (e) {
    if (e instanceof ApiError && e.uncertain) return intent.send()
    throw e
  }
}
async function renew(l: LoanView) {
  try {
    const r = await sendOnce(loanApi.renewIntent(l.id))
    toast('ok', `已续借《${r.title}》`, `新的应还日期 ${fmtDate(r.dueAt)}`)
  } catch (e) {
    toast('danger', '续借未完成', e instanceof ApiError ? errorHints[e.code] ?? e.message : errorMessage(e))
  }
  load()
}
async function giveBack(l: LoanView) {
  try {
    await sendOnce(returnApi.returnIntent({ loanId: l.id }))
    toast('ok', `已归还《${l.title}》`)
  } catch (e) {
    toast('danger', '归还未完成', e instanceof ApiError ? errorHints[e.code] ?? e.message : errorMessage(e))
  }
  load()
}
async function voidRecord(reason: string) {
  const l = voiding.value!
  if (isLoans.value) await loanApi.cancel(l.id, reason)
  else await returnApi.undo(l.id, reason)
  toast('ok', isLoans.value ? '借阅已作废' : '已撤销归还，记录恢复为在借')
  load()
}
function menu(l: LoanView): MenuItem[] {
  if (!isLoans.value) {
    return [
      { label: '更正归还日期', icon: PencilLine, run: () => (correcting.value = l) },
      { label: '撤销误归还', icon: Undo2, danger: true, run: () => (voiding.value = l) },
    ]
  }
  if (l.status === 'CANCELLED') return []
  const items: MenuItem[] = []
  if (l.status === 'ACTIVE') {
    items.push({ label: '续借', icon: RefreshCw, disabled: l.overdue, run: () => renew(l) })
    items.push({ label: '归还', icon: ArrowDownLeft, run: () => giveBack(l) })
  }
  items.push({ label: '更正日期', icon: PencilLine, run: () => (correcting.value = l) })
  items.push({ label: '作废记录', icon: Ban, danger: true, run: () => (voiding.value = l) })
  return items
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">{{ isLoans ? '借书记录' : '还书记录' }}</h1>
        <p class="page-sub">
          {{ isLoans ? '每条借阅一行，按借出日期筛选。作废记录默认隐藏，可在状态中选择查看。' : '只包含已归还的借阅，按归还日期筛选。' }}
        </p>
      </div>
    </header>
    <div class="toolbar">
      <label class="search">
        <Search aria-hidden="true" />
        <input v-model="q" class="input" type="search" placeholder="学号、姓名、书名或登录号" aria-label="搜索记录" />
      </label>
      <select v-if="isLoans" v-model="status" class="select w-auto" aria-label="状态">
        <option value="">全部有效</option>
        <option value="ACTIVE">在借</option>
        <option value="RETURNED">已归还</option>
        <option value="CANCELLED">已作废</option>
      </select>
      <label class="range">
        <span class="muted">{{ isLoans ? '借出' : '归还' }}</span>
        <input v-model="from" class="input" type="date" aria-label="开始日期" />
        <span class="muted">至</span>
        <input v-model="to" class="input" type="date" aria-label="结束日期" />
      </label>
    </div>
    <ErrorNotice v-if="error" :error="error" :retry="load" />
    <div v-else class="table-wrap">
      <SkeletonRows v-if="loading && !data" :rows="10" :cols="6" />
      <table v-else-if="data?.items.length" class="table" :class="{ dim: loading }">
        <thead>
          <tr>
            <th>读者</th><th>书名</th><th>登录号</th><th>借出</th>
            <th>{{ isLoans ? '应还' : '归还' }}</th><th>状态</th><th class="col-actions"><span class="sr-only">操作</span></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="l in data.items" :key="l.id">
            <td class="nowrap">{{ l.studentName }} <span class="mono muted small">{{ l.studentNo }}</span></td>
            <td class="serif tt"><span class="clamp-2">{{ l.title }}</span></td>
            <td class="mono small nowrap">{{ l.accessionNo }}</td>
            <td class="num small nowrap">{{ fmtDate(l.borrowedAt) }}</td>
            <td class="num small nowrap">
              <template v-if="isLoans">
                {{ fmtDate(l.dueAt) }}
                <span v-if="l.renewCount" class="muted">· 已续借</span>
              </template>
              <template v-else>{{ fmtDate(l.returnedAt) }}</template>
            </td>
            <td class="nowrap">
              <span v-if="l.status === 'ACTIVE'" class="badge" :class="l.overdue ? 'danger' : 'info'">{{ l.overdue ? dueInfo(l.dueAt, true).label : '在借' }}</span>
              <span v-else class="badge" :class="loanStatus[l.status].tone">{{ loanStatus[l.status].label }}</span>
              <p v-if="l.status === 'CANCELLED' && l.cancellationReason" class="xs muted truncate reason" :title="l.cancellationReason">{{ l.cancellationReason }}</p>
            </td>
            <td class="col-actions">
              <RowMenu v-if="menu(l).length" :items="menu(l)" :label="`${l.title} 的操作`" />
            </td>
          </tr>
        </tbody>
      </table>
      <EmptyState v-else :icon="ScrollText" title="没有符合条件的记录" text="调整关键词、状态或日期范围再试。" />
    </div>
    <Pager v-if="data && data.totalElements > 0" :page="data.page" :total-pages="data.totalPages" :total="data.totalElements" :size="size" @go="go" />

    <CorrectionDialog :loan="correcting" :kind="kind" @close="correcting = null" @saved="() => { toast('ok', '已更正'); load() }" />
    <ReasonDialog
      :open="!!voiding"
      :title="isLoans ? '作废借阅记录' : '撤销误归还'"
      :confirm-text="isLoans ? '作废' : '撤销归还'"
      :action="voidRecord"
      @close="voiding = null"
    >
      <template v-if="isLoans">
        作废后记录保留但不再计入流通。{{ voiding?.status === 'ACTIVE' ? '这一册会恢复为在架可借。' : '已归还的记录作废不改变当前库存。' }}
      </template>
      <template v-else>撤销后这条借阅恢复为在借。若这一册已被别人借走或学生额度已满，后端会拒绝。</template>
    </ReasonDialog>
  </div>
</template>

<style scoped>
.search { position: relative; width: min(340px, 100%); }
.search svg { position: absolute; left: 10px; top: 10px; width: 16px; height: 16px; color: var(--ink-3); }
.search .input { padding-left: 34px; }
.w-auto { width: auto; }
.range { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); }
.range .input { width: 150px; }
.small { font-size: var(--t-sm); }
.tt { max-width: 340px; font-size: 15px; }
.dim { opacity: 0.6; }
.reason { max-width: 160px; margin-top: 2px; }
</style>
