<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { ArrowLeft, MapPin, ExternalLink, MessageSquareText, LoaderCircle, Quote } from 'lucide-vue-next'
import { ai, books as bookApi } from '@/api/endpoints'
import { ApiError, errorMessage } from '@/api/http'
import type { ActionView, Answer, BookView, CopyView, WorkView } from '@/api/types'
import { copyStatus, errorHints } from '@/lib/format'
import BookCover from '@/components/BookCover.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import Modal from '@/components/Modal.vue'
import ActionCard from '@/components/ActionCard.vue'

const route = useRoute()
const router = useRouter()
const book = ref<BookView | null>(null)
const copies = ref<CopyView[]>([])
const work = ref<WorkView | null>(null)
const loading = ref(true)
const error = ref<unknown>(null)

async function load() {
  const id = Number(route.params.id)
  loading.value = true
  error.value = null
  try {
    const [b, c] = await Promise.all([bookApi.get(id), bookApi.copies(id)])
    book.value = b
    copies.value = c.items.filter((x) => !x.archived)
    work.value = b.workId ? await bookApi.work(b.workId).catch(() => null) : null
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}
watch(() => route.params.id, load, { immediate: true })

const others = computed(() => work.value?.editions.filter((e) => e.id !== book.value?.id) ?? [])
const facts = computed(() => {
  const b = book.value
  if (!b) return []
  return [
    ['出版社', b.publisher],
    ['出版日期', b.publicationDate],
    ['版次', b.editionStatement],
    ['ISBN', b.isbn13],
    ['译者', b.translators],
    ['卷册', b.volume],
    ['分类', b.category],
  ].filter(([, v]) => v) as [string, string][]
})

// ---------- 借这一册：准备确认卡 ----------
const sheet = ref<{ copy: CopyView; action: ActionView | null; error: string; busy: boolean } | null>(null)
async function prepare(copy: CopyView) {
  sheet.value = { copy, action: null, error: '', busy: true }
  try {
    sheet.value.action = await ai.prepareBorrow(copy.accessionNo)
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

// ---------- 关于本书提问（有引用的独立问答） ----------
const question = ref('')
const asking = ref(false)
const answer = ref<Answer | null>(null)
const askError = ref<unknown>(null)
async function ask() {
  if (!question.value.trim() || !book.value) return
  asking.value = true
  askError.value = null
  answer.value = null
  try {
    answer.value = await ai.ask(question.value.trim(), book.value.id)
  } catch (e) {
    askError.value = e
  } finally {
    asking.value = false
  }
}
</script>

<template>
  <div class="detail">
    <button class="btn btn-quiet back" type="button" @click="router.back()"><ArrowLeft />返回</button>
    <ErrorNotice v-if="error" :error="error" :retry="load" />
    <div v-else-if="loading && !book" class="sk" aria-busy="true"><div class="g1"></div><div class="g2"></div></div>
    <article v-else-if="book">
      <header class="head">
        <BookCover :title="book.title" :authors="book.authors" :cover-url="book.coverUrl" :seed="book.isbn13" :width="148" />
        <div class="hd">
          <p v-if="book.category" class="cat">{{ book.category }}</p>
          <h1 class="serif">{{ book.title }}</h1>
          <p v-if="book.subtitle" class="serif sub">{{ book.subtitle }}</p>
          <p class="au">{{ book.authors }}<span v-if="book.translators" class="muted"> · {{ book.translators }} 译</span></p>
          <p class="avail">
            <span v-if="book.availableCount > 0" class="badge ok">{{ book.availableCount }} 册在架可借</span>
            <span v-else class="badge warn">全部借出</span>
            <span class="muted small">馆藏 {{ book.copyCount }} 册</span>
          </p>
        </div>
      </header>

      <section class="block">
        <h2 class="sec-t">选择一册</h2>
        <p class="muted small lead">借阅登记的是具体的一册（馆藏登录号），请按书脊或书后标签核对后选择。</p>
        <ul class="copies">
          <li v-for="c in copies" :key="c.id" :class="{ off: c.circulationStatus !== 'AVAILABLE' }">
            <div class="ci">
              <p class="mono acc">{{ c.accessionNo }}</p>
              <p class="small muted loc"><MapPin aria-hidden="true" />{{ c.location || '位置未登记' }}<template v-if="c.callNumber"> · {{ c.callNumber }}</template></p>
            </div>
            <span class="badge" :class="copyStatus[c.circulationStatus].tone">{{ copyStatus[c.circulationStatus].label }}</span>
            <button v-if="c.circulationStatus === 'AVAILABLE'" class="btn btn-sm btn-primary" type="button" @click="prepare(c)">借这一册</button>
          </li>
          <li v-if="!copies.length" class="muted small">这个版本暂无实体馆藏。</li>
        </ul>
      </section>

      <section class="block">
        <h2 class="sec-t">书目信息</h2>
        <dl class="dl facts">
          <template v-for="[k, v] in facts" :key="k">
            <dt>{{ k }}</dt>
            <dd :class="{ mono: k === 'ISBN' }">{{ v }}</dd>
          </template>
        </dl>
        <p v-if="book.description" class="desc">{{ book.description }}</p>
        <p v-else class="muted small desc">出版方书目未提供简介，本馆不以生成内容代替。</p>
        <a v-if="book.sourceUrl?.startsWith('http')" class="small src" :href="book.sourceUrl" target="_blank" rel="noopener noreferrer">
          书目来源 <ExternalLink />
        </a>
      </section>

      <section v-if="others.length" class="block">
        <h2 class="sec-t">同一作品的其它版本</h2>
        <ul class="others">
          <li v-for="e in others" :key="e.id">
            <RouterLink :to="`/book/${e.id}`" class="serif">{{ e.title }}</RouterLink>
            <span class="muted small">{{ e.publisher }} · {{ e.editionStatement || '版次未注明' }} · {{ e.availableCount }} 册可借</span>
          </li>
        </ul>
      </section>

      <section class="block ask">
        <h2 class="sec-t"><MessageSquareText aria-hidden="true" />关于这本书提问</h2>
        <p class="muted small lead">回答只依据本馆已导入的书目、简介和规则资料，并标注来源；没有资料时会直说。</p>
        <form class="ask-row" @submit.prevent="ask">
          <input v-model="question" class="input" maxlength="1000" placeholder="例如：这本书适合什么基础的读者？" aria-label="问题" />
          <button class="btn" type="submit" :disabled="asking || !question.trim()"><LoaderCircle v-if="asking" class="spin" />提问</button>
        </form>
        <ErrorNotice v-if="askError" :error="askError" inline />
        <div v-if="answer" class="answer">
          <p v-if="!answer.supported" class="badge warn plain">资料不足</p>
          <p class="atext">{{ answer.answer }}</p>
          <ol v-if="answer.citations.length" class="cites">
            <li v-for="c in answer.citations" :key="c.id">
              <Quote aria-hidden="true" /><b>{{ c.id }}</b> {{ c.title }} <span class="muted">— {{ c.excerpt }}</span>
            </li>
          </ol>
        </div>
      </section>
    </article>

    <Modal :open="!!sheet" title="借阅确认" @close="sheet = null">
      <div v-if="sheet">
        <p v-if="sheet.busy" class="muted">正在准备确认卡…</p>
        <ErrorNotice v-else-if="sheet.error" :error="sheet.error" inline />
        <template v-else-if="sheet.action">
          <p class="muted small sheet-hint">自助登记不核验书是否在你手中，请确认已从书架取下这一册。</p>
          <ActionCard :action="sheet.action" @updated="onAction" />
        </template>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.detail {
  padding-top: 10px;
}
.back {
  margin-left: -10px;
  margin-bottom: 8px;
}
.head {
  display: flex;
  gap: 28px;
  align-items: flex-end;
  padding-bottom: 24px;
  border-bottom: 1px solid var(--rule);
}
.hd {
  min-width: 0;
}
.cat {
  font-size: var(--t-xs);
  color: var(--accent-text);
  font-weight: 600;
  letter-spacing: 0.06em;
  margin-bottom: 6px;
}
h1 {
  font-size: 30px;
  line-height: 1.3;
}
.sub {
  margin-top: 4px;
  font-size: 18px;
  color: var(--ink-2);
}
.au {
  margin-top: 10px;
  font-size: var(--t-lg);
  color: var(--ink-2);
}
.avail {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
}
.small {
  font-size: var(--t-sm);
}
.block {
  padding: 22px 0;
  border-bottom: 1px solid var(--rule);
}
.block:last-child {
  border-bottom: 0;
}
.sec-t {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--t-lg);
  margin-bottom: 6px;
}
.sec-t svg {
  width: 18px;
  height: 18px;
  color: var(--ink-3);
}
.lead {
  margin-bottom: 12px;
}
.copies {
  list-style: none;
  padding: 0;
  margin: 0;
  border: 1px solid var(--rule);
  border-radius: var(--r-3);
  background: var(--surface);
}
.copies li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
}
.copies li + li {
  border-top: 1px solid var(--rule);
}
.copies li.off .acc {
  color: var(--ink-3);
}
.ci {
  flex: 1;
  min-width: 0;
}
.acc {
  font-size: var(--t-md);
}
.loc {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
}
.loc svg {
  width: 13px;
  height: 13px;
  flex: none;
}
.facts {
  margin-top: 8px;
}
.desc {
  margin-top: 14px;
  line-height: 1.85;
  white-space: pre-line;
}
.src {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-top: 10px;
}
.src svg {
  width: 12px;
  height: 12px;
}
.others {
  list-style: none;
  padding: 0;
  margin: 8px 0 0;
}
.others li {
  display: flex;
  flex-direction: column;
  padding: 6px 0;
}
.ask-row {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}
.answer {
  margin-top: 12px;
  padding: 14px 16px;
  border-radius: var(--r-3);
  background: var(--surface);
  border: 1px solid var(--rule);
}
.atext {
  white-space: pre-line;
  line-height: 1.8;
}
.cites {
  list-style: none;
  padding: 10px 0 0;
  margin: 10px 0 0;
  border-top: 1px dashed var(--rule-strong);
  font-size: var(--t-sm);
}
.cites svg {
  width: 12px;
  height: 12px;
  margin-right: 4px;
  color: var(--ink-4);
}
.sheet-hint {
  margin-bottom: 12px;
}
.sk .g1 {
  height: 220px;
  border-radius: var(--r-3);
  background: var(--paper-2);
}
.sk .g2 {
  height: 140px;
  margin-top: 16px;
  border-radius: var(--r-3);
  background: var(--paper-2);
}
.spin {
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 760px) {
  .head {
    gap: 16px;
    align-items: flex-start;
  }
  .head :deep(.cover) {
    width: 104px !important;
    --s: 1.08 !important;
  }
  h1 {
    font-size: 21px;
  }
  .sub {
    font-size: var(--t-md);
  }
  .au {
    font-size: var(--t-md);
    margin-top: 6px;
  }
  .avail {
    flex-wrap: wrap;
    gap: 6px 10px;
  }
  .copies li {
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 10px 12px;
  }
  .copies li .btn {
    grid-column: 1 / -1;
    height: 38px;
  }
}
</style>
