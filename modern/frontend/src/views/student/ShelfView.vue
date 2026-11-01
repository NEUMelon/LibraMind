<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { BookMarked, RefreshCw, ChevronRight, History, CircleAlert } from 'lucide-vue-next'
import { ai, me } from '@/api/endpoints'
import { errorMessage } from '@/api/http'
import type { ActionView, Eligibility, LoanView } from '@/api/types'
import { dueInfo, errorHints, fmtDate } from '@/lib/format'
import { useSession } from '@/stores/session'
import { cachedBook, loadBook } from '@/stores/bookCache'
import { ApiError } from '@/api/http'
import BookCover from '@/components/BookCover.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import Modal from '@/components/Modal.vue'
import ActionCard from '@/components/ActionCard.vue'

const session = useSession()
const loans = ref<LoanView[]>([])
const history = ref<LoanView[]>([])
const eligibility = ref<Eligibility | null>(null)
const loading = ref(true)
const error = ref<unknown>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    const [l, e, h] = await Promise.all([me.loans(), me.eligibility(), me.history({ size: 6, status: 'RETURNED' })])
    loans.value = l.items.filter((x) => x.status === 'ACTIVE')
    eligibility.value = e
    history.value = h.items.filter((x) => x.status === 'RETURNED').slice(0, 5)
    ;[...loans.value, ...history.value].forEach((x) => loadBook(x.editionId))
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}
onMounted(load)

const name = computed(() => loans.value[0]?.studentName ?? history.value[0]?.studentName ?? session.user?.username ?? '')
const overdue = computed(() => loans.value.filter((l) => l.overdue))

// ---------- 续借：先准备确认卡，由用户点按钮执行 ----------
const sheet = ref<{ loan: LoanView; action: ActionView | null; error: string; busy: boolean } | null>(null)
async function prepareRenew(loan: LoanView) {
  sheet.value = { loan, action: null, error: '', busy: true }
  try {
    sheet.value.action = await ai.prepareRenew(loan.id)
  } catch (e) {
    sheet.value.error = e instanceof ApiError ? errorHints[e.code] ?? e.message : errorMessage(e)
  } finally {
    if (sheet.value) sheet.value.busy = false
  }
}
function onAction(a: ActionView) {
  if (sheet.value) sheet.value.action = a
  if (a.status === 'SUCCEEDED') load()
}
function closeSheet() {
  sheet.value = null
}
function renewBlocked(l: LoanView) {
  // 续借上限由后端配置决定，这里只根据服务端给出的 overdue 预先停用。
  return l.overdue ? '逾期不能续借' : ''
}
</script>

<template>
  <div class="shelf">
    <header class="hello">
      <div>
        <p class="muted small">我的书架</p>
        <h1 class="serif">{{ name }}</h1>
      </div>
      <div v-if="eligibility" class="quota" :aria-label="`在借 ${eligibility.currentBorrowedCount} 册，上限 ${eligibility.limit} 册`">
        <span class="slots" aria-hidden="true">
          <i v-for="n in eligibility.limit" :key="n" :class="{ used: n <= eligibility.currentBorrowedCount }"></i>
        </span>
        <span class="small ink-2 num">在借 {{ eligibility.currentBorrowedCount }}/{{ eligibility.limit }} · 还可借 {{ eligibility.remaining }}</span>
      </div>
    </header>

    <div v-if="overdue.length" class="alert" role="alert">
      <CircleAlert aria-hidden="true" />
      <p>有 {{ overdue.length }} 册已逾期，请尽快到流通台归还。逾期期间不能续借。</p>
    </div>

    <ErrorNotice v-if="error" :error="error" :retry="load" />

    <section v-else aria-labelledby="h-current">
      <h2 id="h-current" class="sec-t">正在借阅</h2>
      <div v-if="loading && !loans.length" class="loading-cards" aria-busy="true">
        <div v-for="n in 2" :key="n" class="ghost"></div>
      </div>
      <ul v-else-if="loans.length" class="loans">
        <li v-for="l in loans" :key="l.id" class="loan" :class="{ late: l.overdue }">
          <RouterLink :to="`/book/${l.editionId}`" class="cover-link" :aria-label="`查看《${l.title}》`">
            <BookCover
              :title="l.title"
              :authors="cachedBook(l.editionId)?.authors"
              :cover-url="cachedBook(l.editionId)?.coverUrl"
              :seed="cachedBook(l.editionId)?.isbn13 || l.title"
              :width="72"
            />
          </RouterLink>
          <div class="body">
            <RouterLink :to="`/book/${l.editionId}`" class="t serif">{{ l.title }}</RouterLink>
            <p class="muted small truncate">{{ cachedBook(l.editionId)?.authors }}</p>
            <p class="due">
              <span class="badge" :class="dueInfo(l.dueAt, l.overdue).tone">{{ dueInfo(l.dueAt, l.overdue).label }}</span>
              <span class="small ink-2 num">应还 {{ fmtDate(l.dueAt) }}</span>
              <span v-if="l.renewCount" class="muted xs">已续借 {{ l.renewCount }} 次</span>
            </p>
            <div class="row-foot">
              <span class="mono muted xs">{{ l.accessionNo }}</span>
              <button
                class="btn btn-sm"
                type="button"
                :disabled="!!renewBlocked(l)"
                :title="renewBlocked(l) || '续借这一册'"
                @click="prepareRenew(l)"
              >
                <RefreshCw />{{ renewBlocked(l) || '续借' }}
              </button>
            </div>
          </div>
        </li>
      </ul>
      <EmptyState v-else :icon="BookMarked" title="书架是空的" text="找到想读的书后，在图书详情里选择具体的一册登记借阅。" compact>
        <RouterLink to="/search" class="btn btn-primary">去找书</RouterLink>
      </EmptyState>
    </section>

    <section v-if="!error && history.length" class="past" aria-labelledby="h-past">
      <div class="sec-row">
        <h2 id="h-past" class="sec-t">最近归还</h2>
        <RouterLink to="/history" class="more small">全部历史 <ChevronRight /></RouterLink>
      </div>
      <ul class="hist">
        <li v-for="h in history" :key="h.id">
          <History class="hi" aria-hidden="true" />
          <RouterLink :to="`/book/${h.editionId}`" class="serif truncate">{{ h.title }}</RouterLink>
          <span class="muted xs num nowrap">{{ fmtDate(h.returnedAt) }} 归还</span>
        </li>
      </ul>
    </section>

    <Modal :open="!!sheet" title="续借" @close="closeSheet">
      <div v-if="sheet">
        <p v-if="sheet.busy" class="muted">正在准备确认卡…</p>
        <ErrorNotice v-else-if="sheet.error" :error="sheet.error" inline />
        <template v-else-if="sheet.action">
          <p class="muted small sheet-hint">请核对后点击确认。确认卡 5 分钟内有效，取消不会改变借阅。</p>
          <ActionCard :action="sheet.action" @updated="onAction" />
        </template>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.shelf {
  padding-top: 20px;
}
.hello {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding-bottom: 18px;
  margin-bottom: 18px;
  border-bottom: 1px solid var(--rule);
}
h1 {
  font-size: 28px;
  margin-top: 2px;
}
.small {
  font-size: var(--t-sm);
}
.xs {
  font-size: var(--t-xs);
}
.quota {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}
.slots {
  display: flex;
  gap: 4px;
}
.slots i {
  width: 14px;
  height: 20px;
  border-radius: 2px;
  border: 1.5px solid var(--rule-strong);
}
.slots i.used {
  background: var(--accent);
  border-color: var(--accent);
}
.alert {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 12px 14px;
  margin-bottom: 18px;
  border-radius: var(--r-3);
  background: var(--danger-soft);
  color: var(--danger);
}
.alert svg {
  width: 18px;
  height: 18px;
  flex: none;
  margin-top: 2px;
}
.sec-t {
  font-size: var(--t-md);
  font-weight: 600;
  color: var(--ink-2);
  margin-bottom: 12px;
}
.loans {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 12px;
}
.loan {
  display: flex;
  gap: 16px;
  padding: 16px;
  border: 1px solid var(--rule);
  border-radius: var(--r-4);
  background: var(--surface);
}
.loan.late {
  border-color: color-mix(in srgb, var(--danger) 40%, var(--rule));
}
.cover-link {
  flex: none;
}
.body {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
}
.t {
  color: var(--ink);
  font-size: 17px;
  line-height: 1.4;
  font-weight: 600;
}
.due {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.row-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: auto;
  padding-top: 10px;
}
.loading-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 12px;
}
.ghost {
  height: 142px;
  border-radius: var(--r-4);
  background: var(--paper-2);
  animation: pulse 1.4s infinite ease-in-out;
}
@keyframes pulse {
  50% {
    opacity: 0.5;
  }
}
.past {
  margin-top: 32px;
}
.sec-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.more {
  display: inline-flex;
  align-items: center;
}
.more svg {
  width: 14px;
  height: 14px;
}
.hist {
  list-style: none;
  margin: 0;
  padding: 0;
  border-top: 1px solid var(--rule);
}
.hist li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 2px;
  border-bottom: 1px solid var(--rule);
}
.hist a {
  color: var(--ink);
  flex: 1;
  min-width: 0;
}
.hi {
  width: 15px;
  height: 15px;
  color: var(--ink-4);
  flex: none;
}
.sheet-hint {
  margin-bottom: 12px;
}
@media (max-width: 760px) {
  .shelf {
    padding-top: 14px;
  }
  h1 {
    font-size: 24px;
  }
  .loans,
  .loading-cards {
    grid-template-columns: 1fr;
  }
  .loan {
    padding: 14px;
    gap: 14px;
  }
  .quota {
    align-items: flex-start;
  }
}
</style>
