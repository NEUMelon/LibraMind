<script setup lang="ts">
// 借阅历史：本人全部借阅记录，按借出月份分组。数据来自 /me/loan-history，后端只返回本人的记录。
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { Search, History } from 'lucide-vue-next'
import { me } from '@/api/endpoints'
import type { LoanView, Page } from '@/api/types'
import { dueInfo, fmtDate, loanStatus } from '@/lib/format'
import { cachedBook, loadBook } from '@/stores/bookCache'
import BookCover from '@/components/BookCover.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import Pager from '@/components/Pager.vue'

const route = useRoute()
const router = useRouter()
const FILTERS = [
  { key: '', label: '全部' },
  { key: 'ACTIVE', label: '在借' },
  { key: 'RETURNED', label: '已归还' },
] as const

const status = ref(String(route.query.status ?? ''))
const q = ref(String(route.query.q ?? ''))
const page = ref(Number(route.query.page ?? 1) || 1)
const size = 20
const data = ref<Page<LoanView> | null>(null)
const loading = ref(false)
const error = ref<unknown>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    data.value = await me.history({ status: status.value || undefined, q: q.value.trim(), page: page.value, size })
    data.value.items.forEach((l) => loadBook(l.editionId))
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}
function sync() {
  router.replace({ query: { ...(status.value && { status: status.value }), ...(q.value.trim() && { q: q.value.trim() }), ...(page.value > 1 && { page: String(page.value) }) } })
  load()
}
let t = 0
watch(q, () => {
  clearTimeout(t)
  t = window.setTimeout(() => { page.value = 1; sync() }, 300)
})
function pick(s: string) {
  status.value = s
  page.value = 1
  sync()
}
onMounted(load)

const groups = computed(() => {
  const out: { month: string; items: LoanView[] }[] = []
  for (const l of data.value?.items ?? []) {
    const d = new Date(l.borrowedAt)
    const month = `${d.getFullYear()} 年 ${d.getMonth() + 1} 月`
    const last = out[out.length - 1]
    if (last?.month === month) last.items.push(l)
    else out.push({ month, items: [l] })
  }
  return out
})
function line(l: LoanView) {
  if (l.status === 'ACTIVE') return `应还 ${fmtDate(l.dueAt)}`
  if (l.status === 'RETURNED') return `${fmtDate(l.returnedAt)} 归还`
  return '记录已作废'
}
</script>

<template>
  <div class="history">
    <header class="head">
      <h1 class="serif">借阅历史</h1>
      <p v-if="data" class="muted small num">共 {{ data.totalElements }} 条</p>
    </header>

    <div class="bar">
      <div class="seg" role="radiogroup" aria-label="借阅状态">
        <button v-for="f in FILTERS" :key="f.key" type="button" role="radio" :aria-checked="status === f.key" :class="{ on: status === f.key }" @click="pick(f.key)">
          {{ f.label }}
        </button>
      </div>
      <label class="searchbox">
        <Search aria-hidden="true" />
        <input v-model="q" class="input" type="search" placeholder="书名或登录号" aria-label="在借阅历史中搜索" />
      </label>
    </div>

    <ErrorNotice v-if="error" :error="error" :retry="load" />
    <div v-else-if="loading && !data" class="ghosts" aria-busy="true"><div v-for="n in 4" :key="n" class="ghost"></div></div>
    <template v-else-if="data?.items.length">
      <section v-for="g in groups" :key="g.month" class="month" :aria-label="g.month">
        <h2 class="mh small muted">{{ g.month }}</h2>
        <ul class="list">
          <li v-for="l in g.items" :key="l.id" :class="{ cancelled: l.status === 'CANCELLED' }">
            <RouterLink :to="`/book/${l.editionId}`" class="cover" tabindex="-1" aria-hidden="true">
              <BookCover :title="l.title" :authors="cachedBook(l.editionId)?.authors" :cover-url="cachedBook(l.editionId)?.coverUrl" :seed="cachedBook(l.editionId)?.isbn13 || l.title" :width="44" />
            </RouterLink>
            <div class="body">
              <RouterLink :to="`/book/${l.editionId}`" class="t serif">{{ l.title }}</RouterLink>
              <p class="meta xs muted">
                <span class="num">{{ fmtDate(l.borrowedAt) }} 借出</span>
                <span class="mono">{{ l.accessionNo }}</span>
                <span v-if="l.renewCount">续借 {{ l.renewCount }} 次</span>
              </p>
            </div>
            <div class="state">
              <span v-if="l.status === 'ACTIVE'" class="badge" :class="dueInfo(l.dueAt, l.overdue).tone">{{ dueInfo(l.dueAt, l.overdue).label }}</span>
              <span v-else class="badge" :class="loanStatus[l.status].tone">{{ loanStatus[l.status].label }}</span>
              <span class="xs muted num">{{ line(l) }}</span>
            </div>
          </li>
        </ul>
      </section>
      <Pager v-if="data.totalPages > 1" :page="data.page" :total-pages="data.totalPages" :total="data.totalElements" :size="size" @go="(p) => { page = p; sync() }" />
    </template>
    <EmptyState v-else :icon="History" :title="q || status ? '没有符合条件的记录' : '还没有借阅记录'" compact>
      <RouterLink v-if="!q && !status" to="/search" class="btn">去找书</RouterLink>
    </EmptyState>
  </div>
</template>

<style scoped>
.history {
  padding-top: 20px;
}
.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14px;
}
h1 {
  font-size: 26px;
}
.bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 18px;
}
.bar .searchbox {
  flex: 1;
  min-width: 200px;
}
.seg {
  display: inline-flex;
  padding: 3px;
  border-radius: var(--r-3);
  background: var(--paper-2);
}
.seg button {
  height: 32px;
  padding: 0 14px;
  border: 0;
  border-radius: var(--r-2);
  background: transparent;
  color: var(--ink-2);
  font-size: var(--t-sm);
  cursor: pointer;
}
.seg button.on {
  background: var(--surface);
  color: var(--ink);
  font-weight: 600;
  box-shadow: 0 1px 2px rgb(0 0 0 / 0.08);
}
.seg button:focus-visible {
  box-shadow: var(--focus);
  outline: none;
}
.month + .month {
  margin-top: 18px;
}
.mh {
  margin-bottom: 6px;
  font-weight: 500;
}
.list {
  list-style: none;
  margin: 0;
  padding: 0;
  border-top: 1px solid var(--rule);
}
.list li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--rule);
}
.cover {
  flex: none;
}
.body {
  flex: 1;
  min-width: 0;
}
.t {
  display: block;
  color: var(--ink);
  font-size: var(--t-md);
  line-height: 1.4;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.t:hover {
  color: var(--accent-text);
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 10px;
  margin-top: 2px;
}
.state {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  flex: none;
}
.cancelled .t {
  color: var(--ink-3);
  text-decoration: line-through;
  text-decoration-color: var(--ink-4);
}
.ghosts {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ghost {
  height: 66px;
  border-radius: var(--r-2);
  background: var(--paper-2);
  animation: pulse 1.4s ease-in-out infinite;
}
@keyframes pulse {
  50% {
    opacity: 0.55;
  }
}
@media (max-width: 520px) {
  .seg {
    width: 100%;
  }
  .seg button {
    flex: 1;
  }
}
</style>
