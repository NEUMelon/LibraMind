<script setup lang="ts">
// 批量导入：读取 JSON 或 CSV → 服务端预检（不写库）→ 执行（任一错误全批回滚）。
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { FileUp, LoaderCircle, CircleCheck, RotateCw, FileWarning } from 'lucide-vue-next'
import { importing } from '@/api/endpoints'
import { ApiError, Intent, errorMessage } from '@/api/http'
import type { BookInput, ImportPreview, ImportResult } from '@/api/types'
import { parseCsv } from '@/lib/csv'
import { toast } from '@/stores/toast'
import EmptyState from '@/components/EmptyState.vue'

const FIELDS: (keyof BookInput)[] = [
  'title', 'subtitle', 'authors', 'translators', 'isbn13', 'publisher', 'publicationDate',
  'editionStatement', 'volume', 'language', 'classificationNo', 'category', 'description', 'sourceUrl',
]
const books = ref<BookInput[]>([])
const fileName = ref('')
const parseError = ref('')
const copiesPerEdition = ref(1)
const preview = ref<ImportPreview | null>(null)
const previewing = ref(false)
const executing = ref(false)
const result = ref<ImportResult | null>(null)
const problem = ref<{ message: string; uncertain: boolean } | null>(null)
let intent: Intent<ImportResult> | null = null

function normalize(raw: Record<string, unknown>): BookInput {
  const b = Object.fromEntries(FIELDS.map((f) => [f, String(raw[f] ?? '').trim()])) as unknown as BookInput
  b.isbn13 = b.isbn13.replace(/[-\s]/g, '')
  b.workId = null
  return b
}

async function onFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  reset()
  fileName.value = file.name
  try {
    const text = await file.text()
    if (/\.json$/i.test(file.name)) {
      const data = JSON.parse(text)
      const list = Array.isArray(data) ? data : data.books
      if (!Array.isArray(list)) throw new Error('JSON 中没有 books 数组')
      books.value = list.map(normalize)
      if (typeof data.copiesPerEdition === 'number') copiesPerEdition.value = data.copiesPerEdition
    } else {
      const rows = parseCsv(text)
      const header = rows.shift()?.map((h) => h.trim()) ?? []
      if (!header.includes('title') || !header.includes('authors')) throw new Error('CSV 第一行需要列名，至少包含 title 和 authors')
      books.value = rows.map((r) => normalize(Object.fromEntries(header.map((h, i) => [h, r[i] ?? '']))))
    }
    if (!books.value.length) throw new Error('文件里没有书目')
    if (books.value.length > 500) throw new Error(`一次最多 500 条，这个文件有 ${books.value.length} 条，请拆分`)
  } catch (err) {
    parseError.value = err instanceof SyntaxError ? 'JSON 格式不正确' : errorMessage(err)
    books.value = []
  }
}

function reset() {
  preview.value = null
  result.value = null
  problem.value = null
  parseError.value = ''
  intent = null
}

async function runPreview() {
  previewing.value = true
  problem.value = null
  result.value = null
  intent = null
  try {
    preview.value = await importing.preview(books.value, copiesPerEdition.value)
  } catch (e) {
    problem.value = { message: errorMessage(e), uncertain: false }
  } finally {
    previewing.value = false
  }
}

async function execute() {
  executing.value = true
  problem.value = null
  intent ??= importing.executeIntent(books.value, copiesPerEdition.value)
  try {
    result.value = await intent.send()
    intent = null
    toast('ok', '导入完成')
  } catch (e) {
    const err = e as ApiError
    if (err.uncertain) problem.value = { message: '网络中断，无法确认是否已导入。按原请求重试不会重复导入。', uncertain: true }
    else {
      intent = null
      problem.value = { message: errorMessage(e), uncertain: false }
    }
  } finally {
    executing.value = false
  }
}

const errorRows = computed(() => new Set(preview.value?.errors.map((e) => e.row) ?? []))
// 后端行号从 1 开始，与表格第一列一致。
const canExecute = computed(() => !!preview.value && preview.value.errors.length === 0 && !result.value)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">批量导入</h1>
        <p class="page-sub">一次 1 至 500 条。先预检，确认无误再执行；执行时任意一条出错，整批都不会写入。</p>
      </div>
    </header>

    <div class="layout">
      <section class="panel">
        <h2 class="h">1. 选择文件</h2>
        <label class="drop">
          <FileUp aria-hidden="true" />
          <span><b>选择 JSON 或 CSV 文件</b></span>
          <span class="muted small">{{ fileName || 'JSON 与演示数据 books-import.json 格式相同；CSV 第一行写列名' }}</span>
          <input type="file" accept=".json,.csv,application/json,text/csv" class="sr-only" @change="onFile" />
        </label>
        <p v-if="parseError" class="error-text" role="alert">{{ parseError }}</p>
        <details class="cols">
          <summary class="small">可用列名</summary>
          <p class="mono xs">{{ FIELDS.join(', ') }}</p>
          <p class="xs muted">title 和 authors 必填；ISBN 校验位不对或批内重复会被拒绝。缺少的字段保持空，不要用生成内容填充。</p>
        </details>

        <h2 class="h">2. 每个版本新增副本</h2>
        <div class="copies">
          <input id="cpe" v-model.number="copiesPerEdition" class="input" type="number" min="0" max="5" @change="preview = null" />
          <label for="cpe" class="small muted">册（0–5）。已存在的版本会更新书目信息。</label>
        </div>

        <div class="acts">
          <button class="btn" type="button" :disabled="!books.length || previewing" @click="runPreview">
            <LoaderCircle v-if="previewing" class="spin" />预检
          </button>
          <button v-if="problem?.uncertain" class="btn btn-primary" type="button" :disabled="executing" @click="execute"><RotateCw />按原请求重试</button>
          <button v-else class="btn btn-primary" type="button" :disabled="!canExecute || executing" @click="execute">
            <LoaderCircle v-if="executing" class="spin" />执行导入
          </button>
        </div>
        <p v-if="problem" class="problem" :class="{ uncertain: problem.uncertain }" role="alert">{{ problem.message }}</p>

        <div v-if="preview" class="result" :class="{ bad: preview.errors.length }">
          <p class="rt">
            <FileWarning v-if="preview.errors.length" aria-hidden="true" /><CircleCheck v-else aria-hidden="true" />
            预检：共 {{ preview.total }} 条，新版本 {{ preview.newEditions }}，已存在 {{ preview.existingEditions }}
          </p>
          <ul v-if="preview.errors.length" class="errs">
            <li v-for="e in preview.errors" :key="e.row + e.message"><span class="mono">第 {{ e.row }} 条</span> {{ e.message }}</li>
          </ul>
          <p v-else class="small muted">没有发现错误，可以执行。</p>
        </div>
        <div v-if="result" class="result done">
          <p class="rt"><CircleCheck aria-hidden="true" />已导入：新建 {{ result.created }}，更新 {{ result.updated }}，新增副本 {{ result.copiesCreated }} 册</p>
          <RouterLink to="/books" class="small">到图书与副本查看</RouterLink>
        </div>
      </section>

      <section class="list">
        <div class="table-wrap">
          <table v-if="books.length" class="table">
            <thead><tr><th class="col-num">#</th><th>书名</th><th>责任者</th><th>ISBN</th><th>出版社</th></tr></thead>
            <tbody>
              <tr v-for="(b, i) in books.slice(0, 200)" :key="i" :class="{ bad: errorRows.has(i + 1) }">
                <td class="col-num muted">{{ i + 1 }}</td>
                <td class="serif">{{ b.title || '（缺书名）' }}</td>
                <td class="small">{{ b.authors || '（缺责任者）' }}</td>
                <td class="mono small">{{ b.isbn13 }}</td>
                <td class="small">{{ b.publisher }}</td>
              </tr>
            </tbody>
          </table>
          <EmptyState v-else title="还没有选择文件" text="读取后会在这里列出将要导入的书目，最多显示前 200 条。" />
        </div>
        <p v-if="books.length > 200" class="small muted more">另有 {{ books.length - 200 }} 条未显示。</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: 24px;
  align-items: start;
}
.panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  position: sticky;
  top: 20px;
}
.h {
  font-size: var(--t-md);
  margin-top: 6px;
}
.drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 22px 16px;
  border: 1.5px dashed var(--rule-strong);
  border-radius: var(--r-3);
  background: var(--surface);
  text-align: center;
  cursor: pointer;
}
.drop:hover {
  border-color: var(--accent);
}
.drop:focus-within {
  box-shadow: var(--focus);
}
.drop svg {
  width: 22px;
  height: 22px;
  color: var(--ink-3);
}
.cols summary {
  cursor: pointer;
  color: var(--ink-2);
}
.cols p {
  margin-top: 6px;
}
.copies {
  display: flex;
  align-items: center;
  gap: 10px;
}
.copies .input {
  width: 80px;
}
.acts {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
.problem {
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
.result {
  padding: 12px 14px;
  border-radius: var(--r-3);
  background: var(--ok-soft);
}
.result.bad {
  background: var(--danger-soft);
}
.rt {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 500;
}
.rt svg {
  width: 16px;
  height: 16px;
}
.errs {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: var(--t-sm);
  max-height: 220px;
  overflow: auto;
}
tr.bad td {
  background: var(--danger-soft);
}
.more {
  margin-top: 8px;
}
@media (max-width: 960px) {
  .layout {
    grid-template-columns: 1fr;
  }
  .panel {
    position: static;
  }
}
</style>
