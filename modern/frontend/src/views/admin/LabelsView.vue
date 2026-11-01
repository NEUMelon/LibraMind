<script setup lang="ts">
// 条码标签：选择副本 → 生成标签清单 → 浏览器打印。条码图片由后端生成（CODE128，内容即馆藏登录号）。
import { computed, onMounted, ref, watch } from 'vue'
import { Search, Printer, X, Barcode, LoaderCircle } from 'lucide-vue-next'
import { copies as copyApi } from '@/api/endpoints'
import { errorMessage } from '@/api/http'
import type { CopyView, Label, Page } from '@/api/types'
import { copyStatus } from '@/lib/format'
import Pager from '@/components/Pager.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'

const q = ref('')
const page = ref(1)
const size = 20
const data = ref<Page<CopyView> | null>(null)
const loading = ref(false)
const error = ref<unknown>(null)
const picked = ref<CopyView[]>([])
const labels = ref<Label[]>([])
const making = ref(false)
const makeError = ref('')

async function load() {
  loading.value = true
  error.value = null
  try {
    data.value = await copyApi.list({ q: q.value.trim(), page: page.value, size })
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}
let t = 0
watch(q, () => {
  clearTimeout(t)
  t = window.setTimeout(() => { page.value = 1; load() }, 280)
})
onMounted(load)

const pickedIds = computed(() => new Set(picked.value.map((c) => c.id)))
function toggle(c: CopyView) {
  if (pickedIds.value.has(c.id)) picked.value = picked.value.filter((x) => x.id !== c.id)
  else if (picked.value.length < 100) picked.value.push(c)
  labels.value = []
}
function pickPage() {
  for (const c of data.value?.items ?? []) if (!pickedIds.value.has(c.id) && picked.value.length < 100) picked.value.push(c)
  labels.value = []
}
async function make() {
  making.value = true
  makeError.value = ''
  try {
    labels.value = await copyApi.labels(picked.value.map((c) => c.id))
  } catch (e) {
    makeError.value = errorMessage(e)
  } finally {
    making.value = false
  }
}
function print() {
  window.print()
}
</script>

<template>
  <div class="page labels-page">
    <header class="page-head no-print">
      <div>
        <h1 class="page-title">条码标签</h1>
        <p class="page-sub">每张标签对应一册，条码内容就是馆藏登录号。一次最多 100 张。</p>
      </div>
    </header>

    <div class="layout no-print">
      <section>
        <div class="toolbar">
          <label class="searchbox">
            <Search aria-hidden="true" />
            <input v-model="q" class="input" type="search" placeholder="登录号、书名或 ISBN" aria-label="搜索副本" />
          </label>
          <button class="btn btn-sm" type="button" :disabled="!data?.items.length" @click="pickPage">选中本页</button>
        </div>
        <ErrorNotice v-if="error" :error="error" :retry="load" />
        <div v-else class="table-wrap">
          <SkeletonRows v-if="loading && !data" :rows="8" :cols="4" />
          <table v-else-if="data?.items.length" class="table">
            <thead><tr><th style="width: 36px"><span class="sr-only">选择</span></th><th>登录号</th><th>书名</th><th>位置</th><th>状态</th></tr></thead>
            <tbody>
              <tr v-for="c in data.items" :key="c.id" :class="{ 'is-selected': pickedIds.has(c.id) }" @click="toggle(c)">
                <td><input type="checkbox" class="cb" :checked="pickedIds.has(c.id)" :aria-label="`选择 ${c.accessionNo}`" @click.stop="toggle(c)" /></td>
                <td class="mono nowrap">{{ c.accessionNo }}</td>
                <td class="serif">{{ c.title }}</td>
                <td class="small">{{ c.location || '—' }}</td>
                <td><span class="badge" :class="copyStatus[c.circulationStatus].tone">{{ copyStatus[c.circulationStatus].label }}</span></td>
              </tr>
            </tbody>
          </table>
          <EmptyState v-else :icon="Barcode" title="没有找到副本" />
        </div>
        <Pager v-if="data && data.totalElements > 0" :page="data.page" :total-pages="data.totalPages" :total="data.totalElements" :size="size" @go="(p) => { page = p; load() }" />
      </section>

      <aside class="tray">
        <p class="section-title">待打印 <span class="num">{{ picked.length }}</span> / 100</p>
        <ul v-if="picked.length" class="picked">
          <li v-for="c in picked" :key="c.id">
            <span class="mono small">{{ c.accessionNo }}</span>
            <button class="btn btn-sm btn-quiet btn-icon" type="button" :aria-label="`移除 ${c.accessionNo}`" @click="toggle(c)"><X /></button>
          </li>
        </ul>
        <p v-else class="muted small">在左侧勾选要打印标签的副本。</p>
        <div class="tray-acts">
          <button class="btn btn-primary" type="button" :disabled="!picked.length || making" @click="make"><LoaderCircle v-if="making" class="spin" />生成标签</button>
          <button v-if="picked.length" class="btn btn-quiet" type="button" @click="() => { picked = []; labels = [] }">清空</button>
        </div>
        <p v-if="makeError" class="error-text">{{ makeError }}</p>
      </aside>
    </div>

    <section v-if="labels.length" class="sheet-wrap">
      <div class="sheet-head no-print">
        <h2 class="h2">标签预览 <span class="muted num">{{ labels.length }}</span></h2>
        <button class="btn btn-primary" type="button" @click="print"><Printer />打印</button>
      </div>
      <div class="sheet">
        <div v-for="l in labels" :key="l.copy.id" class="label">
          <img :src="l.barcodeUrl" :alt="`条码 ${l.copy.accessionNo}`" />
          <p class="acc mono">{{ l.copy.accessionNo }}</p>
          <p class="lt">{{ l.copy.title }}</p>
          <p v-if="l.copy.callNumber" class="call mono">{{ l.copy.callNumber }}</p>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 260px;
  gap: 24px;
  align-items: start;
}
.cb {
  width: 16px;
  height: 16px;
  accent-color: var(--accent);
}
.table tbody tr {
  cursor: pointer;
}
.tray {
  position: sticky;
  top: 20px;
  padding: 14px;
  border: 1px solid var(--rule);
  border-radius: var(--r-3);
  background: var(--surface);
}
.picked {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  max-height: 340px;
  overflow: auto;
}
.picked li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--rule);
}
.tray-acts {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.sheet-wrap {
  margin-top: 28px;
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.h2 {
  font-size: var(--t-lg);
}
.sheet {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 10px;
  padding: 16px;
  background: #fff;
  border: 1px solid var(--rule);
  border-radius: var(--r-2);
}
.label {
  padding: 10px 10px 8px;
  border: 1px dashed #c9c3b8;
  color: #111;
  text-align: center;
  break-inside: avoid;
}
.label img {
  width: 100%;
  height: 54px;
  object-fit: contain;
  image-rendering: pixelated;
}
.acc {
  margin-top: 4px;
  font-size: 12px;
  letter-spacing: 0.04em;
}
.lt {
  font-size: 11px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.call {
  font-size: 11px;
}
@media (max-width: 960px) {
  .layout {
    grid-template-columns: 1fr;
  }
  .tray {
    position: static;
  }
}
@media print {
  .no-print {
    display: none !important;
  }
  .sheet {
    border: 0;
    padding: 0;
    grid-template-columns: repeat(3, 1fr);
  }
}
</style>

<style>
@media print {
  .side,
  .mobile-bar,
  .host {
    display: none !important;
  }
  .shell {
    display: block !important;
  }
  body {
    background: #fff !important;
  }
}
</style>
