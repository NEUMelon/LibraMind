<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { Search, Sparkles, X, LibraryBig, Info } from 'lucide-vue-next'
import { books as bookApi, search as searchApi } from '@/api/endpoints'
import type { BookView, SearchResult } from '@/api/types'
import { degradedText, pubYear } from '@/lib/format'
import BookCover from '@/components/BookCover.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'

const route = useRoute()
const router = useRouter()
const q = ref(String(route.query.q ?? ''))
const smart = ref(route.query.mode === 'smart')
const availableOnly = ref(route.query.available === '1')
const result = ref<SearchResult | null>(null)
const browse = ref<BookView[]>([])
const browsePage = ref(1)
const browseMore = ref(true)
const loading = ref(false)
const error = ref<unknown>(null)
const input = ref<HTMLInputElement>()

async function run() {
  const text = q.value.trim()
  router.replace({ query: { ...(text ? { q: text } : {}), ...(smart.value ? { mode: 'smart' } : {}), ...(availableOnly.value ? { available: '1' } : {}) } })
  error.value = null
  if (!text) {
    result.value = null
    if (!browse.value.length) loadBrowse()
    return
  }
  loading.value = true
  try {
    result.value = smart.value
      ? await searchApi.hybrid(text, 20, availableOnly.value)
      : await searchApi.keyword(text, 20, availableOnly.value)
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}

async function loadBrowse() {
  loading.value = true
  try {
    const page = await bookApi.list({ sort: 'newest', page: browsePage.value, size: 20, available: availableOnly.value || undefined })
    browse.value.push(...page.items)
    browseMore.value = page.page < page.totalPages
    browsePage.value += 1
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}

watch([smart, availableOnly], () => {
  if (!q.value.trim()) {
    browse.value = []
    browsePage.value = 1
  }
  run()
})
function clear() {
  q.value = ''
  run()
  input.value?.focus()
}
onMounted(run)

function meta(b: BookView) {
  return [b.publisher, pubYear(b.publicationDate), b.editionStatement !== '版次未注明' ? b.editionStatement : '']
    .filter(Boolean)
    .join(' · ')
}
</script>

<template>
  <div class="search-page">
    <form class="bar" role="search" @submit.prevent="run">
      <div class="box">
        <Search class="lead" aria-hidden="true" />
        <input
          ref="input"
          v-model="q"
          class="input input-lg"
          type="search"
          enterkeyhint="search"
          :placeholder="smart ? '描述你想找的内容，例如：适合入门的操作系统书' : '书名、作者、ISBN 或出版社'"
          aria-label="检索馆藏"
        />
        <button v-if="q" class="btn btn-quiet btn-icon clear" type="button" aria-label="清除" @click="clear"><X /></button>
      </div>
      <div class="opts">
        <div class="seg" role="radiogroup" aria-label="检索方式">
          <button type="button" role="radio" :aria-checked="!smart" :class="{ on: !smart }" @click="smart = false">关键词</button>
          <button type="button" role="radio" :aria-checked="smart" :class="{ on: smart }" @click="smart = true"><Sparkles />语义混合</button>
        </div>
        <label class="checkbox small"><input v-model="availableOnly" type="checkbox" />只看可借</label>
      </div>
    </form>

    <ErrorNotice v-if="error" :error="error" :retry="run" />

    <template v-else-if="result">
      <p class="summary small muted">
        <template v-if="loading">正在检索…</template>
        <template v-else>
          “{{ result.query }}” 找到 {{ result.hits.length }} 个版本
          <span class="num">· {{ result.latencyMs }} ms</span>
        </template>
      </p>
      <p v-if="result.degraded?.length" class="degraded" role="status">
        <Info aria-hidden="true" />
        {{ degradedText(result.degraded) }}检索暂不可用，以下只是关键词结果。
      </p>
      <ul v-if="result.hits.length" class="results">
        <li v-for="h in result.hits" :key="h.book.id">
          <RouterLink :to="`/book/${h.book.id}`" class="hit">
            <BookCover :title="h.book.title" :authors="h.book.authors" :cover-url="h.book.coverUrl" :seed="h.book.isbn13" :width="64" />
            <div class="hb">
              <p class="t serif">{{ h.book.title }}</p>
              <p class="au truncate">{{ h.book.authors }}</p>
              <p class="m muted truncate">{{ meta(h.book) }}</p>
              <p class="stock">
                <span v-if="h.book.availableCount > 0" class="badge ok">{{ h.book.availableCount }} 册可借</span>
                <span v-else class="badge warn">全部借出</span>
                <span v-if="h.matchReasons?.length" class="why xs muted truncate">{{ h.matchReasons.join(' · ') }}</span>
              </p>
            </div>
          </RouterLink>
        </li>
      </ul>
      <EmptyState
        v-else-if="!loading"
        :icon="LibraryBig"
        title="馆藏里没有找到"
        :text="/^\d{10,13}$/.test(result.query.replace(/-/g, '')) ? '馆内没有这个 ISBN 对应的版本。' : '换个说法，或去掉“只看可借”再试。'"
        compact
      />
    </template>

    <template v-else>
      <h2 class="sec-t">最近入藏</h2>
      <ul class="results">
        <li v-for="b in browse" :key="b.id">
          <RouterLink :to="`/book/${b.id}`" class="hit">
            <BookCover :title="b.title" :authors="b.authors" :cover-url="b.coverUrl" :seed="b.isbn13" :width="64" />
            <div class="hb">
              <p class="t serif">{{ b.title }}</p>
              <p class="au truncate">{{ b.authors }}</p>
              <p class="m muted truncate">{{ meta(b) }}</p>
              <p class="stock">
                <span v-if="b.availableCount > 0" class="badge ok">{{ b.availableCount }} 册可借</span>
                <span v-else class="badge warn">全部借出</span>
              </p>
            </div>
          </RouterLink>
        </li>
      </ul>
      <div class="more">
        <button v-if="browseMore" class="btn" type="button" :disabled="loading" @click="loadBrowse">{{ loading ? '加载中…' : '加载更多' }}</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.search-page {
  padding-top: 18px;
}
.bar {
  position: sticky;
  top: 56px;
  z-index: 10;
  padding: 4px 0 12px;
  background: var(--paper);
}
.box {
  position: relative;
}
.lead {
  position: absolute;
  left: 14px;
  top: 13px;
  width: 18px;
  height: 18px;
  color: var(--ink-3);
}
.box .input {
  padding-left: 42px;
  padding-right: 44px;
  height: 46px;
  border-radius: var(--r-3);
  background: var(--surface);
}
.clear {
  position: absolute;
  right: 6px;
  top: 6px;
}
.opts {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 10px;
}
.seg {
  display: inline-flex;
  padding: 2px;
  border-radius: 999px;
  background: var(--paper-2);
  border: 1px solid var(--rule);
}
.seg button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 12px;
  border: 0;
  border-radius: 999px;
  background: none;
  color: var(--ink-2);
  font-size: var(--t-sm);
  cursor: pointer;
}
.seg button svg {
  width: 13px;
  height: 13px;
}
.seg button.on {
  background: var(--surface);
  color: var(--ink);
  font-weight: 600;
  box-shadow: var(--shadow-1);
}
.small {
  font-size: var(--t-sm);
}
.xs {
  font-size: var(--t-xs);
}
.summary {
  margin: 6px 0 8px;
}
.degraded {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  margin-bottom: 10px;
  border-radius: var(--r-2);
  background: var(--warn-soft);
  color: var(--warn);
  font-size: var(--t-sm);
}
.degraded svg {
  width: 16px;
  height: 16px;
  flex: none;
}
.sec-t {
  font-size: var(--t-md);
  color: var(--ink-2);
  margin: 10px 0 4px;
}
.results {
  list-style: none;
  margin: 0;
  padding: 0;
}
.results li + li {
  border-top: 1px solid var(--rule);
}
.hit {
  display: flex;
  gap: 16px;
  padding: 16px 4px;
  color: inherit;
  text-decoration: none !important;
  border-radius: var(--r-2);
}
.hit:hover .t {
  color: var(--accent-text);
}
.hb {
  min-width: 0;
  flex: 1;
}
.t {
  font-size: 17px;
  font-weight: 600;
  line-height: 1.4;
}
.au {
  margin-top: 3px;
  color: var(--ink-2);
}
.m {
  font-size: var(--t-sm);
  margin-top: 1px;
}
.stock {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  min-width: 0;
}
.more {
  display: flex;
  justify-content: center;
  padding: 18px 0;
}
@media (max-width: 760px) {
  .bar {
    top: 50px;
  }
  .t {
    font-size: 16px;
  }
}
</style>
