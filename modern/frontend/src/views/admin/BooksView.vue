<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { Search, Plus, LibraryBig, FileUp } from 'lucide-vue-next'
import { books as bookApi } from '@/api/endpoints'
import { pubYear } from '@/lib/format'
import type { BookView, Page } from '@/api/types'
import BookCover from '@/components/BookCover.vue'
import Pager from '@/components/Pager.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import EditionDrawer from './EditionDrawer.vue'
import BookFormDialog from './BookFormDialog.vue'
import { toast } from '@/stores/toast'

const route = useRoute()
const router = useRouter()

const q = ref(String(route.query.q ?? ''))
const available = ref(route.query.available === '1')
const sort = ref(String(route.query.sort ?? 'newest'))
const page = ref(Number(route.query.page ?? 1) || 1)
const size = 20

const data = ref<Page<BookView> | null>(null)
const loading = ref(false)
const error = ref<unknown>(null)
const openId = ref<number | null>(route.query.id ? Number(route.query.id) : null)

let ctrl: AbortController | null = null
async function load() {
  ctrl?.abort()
  ctrl = new AbortController()
  loading.value = true
  error.value = null
  try {
    data.value = await bookApi.list({
      q: q.value.trim(), available: available.value ? true : undefined, sort: sort.value, page: page.value, size,
    })
  } catch (e) {
    if ((e as Error).name !== 'AbortError') error.value = e
  } finally {
    loading.value = false
  }
}

function syncQuery() {
  router.replace({
    query: {
      ...(q.value ? { q: q.value } : {}),
      ...(available.value ? { available: '1' } : {}),
      ...(sort.value !== 'newest' ? { sort: sort.value } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {}),
      ...(openId.value ? { id: String(openId.value) } : {}),
    },
  })
}

let debounce = 0
watch(q, () => {
  window.clearTimeout(debounce)
  debounce = window.setTimeout(() => {
    page.value = 1
    syncQuery()
    load()
  }, 280)
})
watch([available, sort], () => {
  page.value = 1
  syncQuery()
  load()
})
function go(p: number) {
  page.value = p
  syncQuery()
  load()
}
function open(id: number) {
  openId.value = id
  syncQuery()
}
function close() {
  openId.value = null
  syncQuery()
}
const creating = ref(false)
function onChanged(book: BookView | null) {
  if (!book) return load()
  const i = data.value?.items.findIndex((b) => b.id === book.id) ?? -1
  if (i >= 0 && data.value) data.value.items[i] = book
}
function onCreated(book: BookView) {
  toast('ok', '已新增出版版本', '接着为它登记实体副本')
  load()
  open(book.id)
}
onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">图书与副本</h1>
        <p class="page-sub">每行是一个出版版本（ISBN）；点开查看并维护它的实体副本。</p>
      </div>
      <div class="head-acts">
        <RouterLink class="btn" to="/import"><FileUp />批量导入</RouterLink>
        <button class="btn btn-primary" type="button" @click="creating = true"><Plus />新增版本</button>
      </div>
    </header>

    <div class="toolbar">
      <label class="search">
        <Search aria-hidden="true" />
        <input v-model="q" class="input" type="search" placeholder="书名、作者、ISBN、出版社" aria-label="搜索图书" />
      </label>
      <label class="checkbox"><input v-model="available" type="checkbox" />只看有可借副本</label>
      <span class="grow"></span>
      <label class="sort">
        <span class="muted">排序</span>
        <select v-model="sort" class="select" aria-label="排序">
          <option value="newest">最近入藏</option>
          <option value="title">书名</option>
          <option value="available">可借数量</option>
        </select>
      </label>
    </div>

    <ErrorNotice v-if="error" :error="error" :retry="load" />
    <div v-else class="table-wrap">
      <SkeletonRows v-if="loading && !data" :rows="8" />
      <table v-else-if="data && data.items.length" class="table" :class="{ dim: loading }">
        <thead>
          <tr>
            <th style="width: 56px"><span class="sr-only">封面</span></th>
            <th>书名 / 责任者</th>
            <th>ISBN</th>
            <th>出版</th>
            <th>分类</th>
            <th class="col-num">副本</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="b in data.items"
            :key="b.id"
            tabindex="0"
            :class="{ 'is-selected': openId === b.id }"
            @click="open(b.id)"
            @keydown.enter="open(b.id)"
          >
            <td><BookCover :title="b.title" :authors="b.authors" :cover-url="b.coverUrl" :seed="b.isbn13" :width="36" /></td>
            <td class="tcell">
              <p class="serif title">{{ b.title }}<span v-if="b.subtitle" class="sub">：{{ b.subtitle }}</span></p>
              <p class="muted small truncate">{{ b.authors }}<template v-if="b.translators"> · {{ b.translators }} 译</template></p>
            </td>
            <td class="mono nowrap small">{{ b.isbn13 || '—' }}</td>
            <td class="small">
              <p class="truncate pub">{{ b.publisher }}</p>
              <p class="muted num">{{ pubYear(b.publicationDate) || '年份未注明' }}<template v-if="b.editionStatement && b.editionStatement !== '版次未注明'"> · {{ b.editionStatement }}</template></p>
            </td>
            <td class="small ink-2">{{ b.category || '—' }}</td>
            <td class="col-num nowrap">
              <span :class="b.availableCount > 0 ? 'avail' : 'none'">{{ b.availableCount }}</span>
              <span class="muted"> / {{ b.copyCount }}</span>
            </td>
          </tr>
        </tbody>
      </table>
      <EmptyState
        v-else
        :icon="LibraryBig"
        :title="q ? `没有找到“${q}”` : '还没有图书'"
        :text="q ? '试试作者名、ISBN 或更短的关键词。' : '可以通过批量导入或版权页编目添加。'"
      />
    </div>
    <Pager v-if="data && data.totalElements > 0" :page="data.page" :total-pages="data.totalPages" :total="data.totalElements" :size="size" @go="go" />

    <EditionDrawer :id="openId" @close="close" @changed="onChanged" />
    <BookFormDialog :open="creating" :book="null" @close="creating = false" @saved="onCreated" />
  </div>
</template>

<style scoped>
.head-acts {
  display: flex;
  gap: 8px;
}
.search {
  position: relative;
  width: min(420px, 100%);
}
.search svg {
  position: absolute;
  left: 10px;
  top: 10px;
  width: 16px;
  height: 16px;
  color: var(--ink-3);
}
.search .input {
  padding-left: 34px;
}
.sort {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--t-sm);
}
.sort .select {
  width: auto;
}
.tcell {
  max-width: 420px;
}
.title {
  font-size: 15px;
  line-height: 1.4;
}
.sub {
  color: var(--ink-2);
  font-weight: 400;
}
.small {
  font-size: var(--t-sm);
}
.pub {
  max-width: 180px;
}
.avail {
  color: var(--ok);
  font-weight: 600;
}
.none {
  color: var(--ink-4);
}
.dim {
  opacity: 0.6;
  transition: opacity 0.15s;
}
@media (max-width: 860px) {
  .table th:nth-child(3), .table td:nth-child(3),
  .table th:nth-child(5), .table td:nth-child(5) {
    display: none;
  }
}
</style>
