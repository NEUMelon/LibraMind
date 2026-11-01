<script setup lang="ts">
// 版权页辅助编目（仅管理员）：视觉模型从版权页提取字段 → 馆员逐项核对修改 → 显式确认才写库。
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ImageUp, LoaderCircle, CircleAlert, TriangleAlert, ClipboardCheck } from 'lucide-vue-next'
import { vision } from '@/api/endpoints'
import { ApiError, errorMessage } from '@/api/http'
import type { BookInput, BookView, VisionResult } from '@/api/types'
import { errorHints } from '@/lib/format'
import { toast } from '@/stores/toast'
import BookFormDialog from './BookFormDialog.vue'

const preview = ref('')
const busy = ref(false)
const error = ref<{ message: string; code: string } | null>(null)
const result = ref<VisionResult | null>(null)
const reviewing = ref(false)
const saved = ref<BookView | null>(null)

async function onPick(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = URL.createObjectURL(file)
  result.value = null
  saved.value = null
  error.value = null
  busy.value = true
  try {
    result.value = await vision.copyrightPage(file)
  } catch (err) {
    const code = err instanceof ApiError ? err.code : ''
    error.value = { message: errorHints[code] ?? errorMessage(err), code }
  } finally {
    busy.value = false
  }
}

const initial = computed<Partial<BookInput>>(() => {
  const x = result.value?.extracted
  if (!x) return {}
  return {
    title: x.title, subtitle: x.subtitle, authors: x.authors, translators: x.translators, isbn13: x.isbn13,
    publisher: x.publisher, publicationDate: x.publicationDate, editionStatement: x.editionStatement,
    volume: x.volume, language: x.language || 'zh', classificationNo: x.classificationNo,
  }
})
const existing = computed(() => (result.value?.outcome === 'EXISTING_EDITION' ? result.value.candidates[0]?.editions.find((e) => e.isbn13 === result.value?.extracted?.isbn13) ?? null : null))
const fieldRows = computed(() => {
  const x = result.value?.extracted
  if (!x) return []
  return [
    ['书名', x.title], ['副书名', x.subtitle], ['责任者', x.authors], ['译者', x.translators], ['ISBN', x.isbn13],
    ['出版社', x.publisher], ['出版日期', x.publicationDate], ['版次', x.editionStatement], ['卷册', x.volume], ['分类号', x.classificationNo],
  ] as [string, string][]
})
function confirm(input: BookInput) {
  return vision.confirmCopyright(result.value!.recognitionId, input)
}
function onSaved(b: BookView) {
  saved.value = b
  toast('ok', '书目已入库', '接着到图书与副本里登记实体副本')
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">版权页编目</h1>
        <p class="page-sub">上传版权页照片，模型只提取看得见的字段。逐项核对后手动保存，识别本身不写入书目。</p>
      </div>
    </header>

    <div class="layout">
      <section class="left">
        <label class="drop">
          <img v-if="preview" :src="preview" alt="版权页照片" class="pv" />
          <span v-else class="ph"><ImageUp aria-hidden="true" /></span>
          <span class="btn">{{ preview ? '换一张' : '选择版权页照片' }}</span>
          <span class="xs muted">JPEG / PNG / WebP，10MB 内；原图默认不保存</span>
          <input type="file" accept="image/jpeg,image/png,image/webp" class="sr-only" @change="onPick" />
        </label>
      </section>

      <section class="right" aria-live="polite">
        <p v-if="busy" class="muted status"><LoaderCircle class="spin" />正在读取版权页…</p>
        <div v-else-if="error" class="err" role="alert">
          <CircleAlert aria-hidden="true" />
          <div>
            <p>{{ error.message }}</p>
            <p v-if="error.code" class="mono xs muted">{{ error.code }}</p>
            <p v-if="error.code === 'AI_UNAVAILABLE'" class="small">也可以直接在图书与副本里手工新增版本。</p>
          </div>
        </div>
        <template v-else-if="result">
          <div v-if="existing" class="exists">
            <TriangleAlert aria-hidden="true" />
            <p>馆内已有这个 ISBN 的版本：<RouterLink :to="`/books?id=${existing.id}`" class="serif">{{ existing.title }}</RouterLink>。不必重复编目，可直接为它增加副本。</p>
          </div>
          <table class="table ext">
            <caption class="section-title">识别出的字段（未入库）</caption>
            <tbody>
              <tr v-for="[k, v] in fieldRows" :key="k">
                <th scope="row">{{ k }}</th>
                <td :class="{ mono: k === 'ISBN', muted: !v }">{{ v || '未识别' }}</td>
              </tr>
            </tbody>
          </table>
          <ul v-if="result.extracted?.confidenceNotes?.length || result.warnings?.length" class="notes xs">
            <li v-for="n in [...(result.warnings ?? []), ...(result.extracted?.confidenceNotes ?? [])]" :key="n">{{ n }}</li>
          </ul>
          <div class="acts">
            <button class="btn btn-primary" type="button" :disabled="!!saved" @click="reviewing = true"><ClipboardCheck />核对并保存</button>
            <span class="xs muted">识别模型：{{ result.model }}</span>
          </div>
          <p v-if="saved" class="ok small">
            已保存为出版版本 <RouterLink :to="`/books?id=${saved.id}`" class="serif">{{ saved.title }}</RouterLink>，请为它新增副本。
          </p>
        </template>
        <p v-else class="muted small idle">识别结果会显示在这里。ISBN 校验不通过时会被清空，需要对照实物填写。</p>
      </section>
    </div>

    <BookFormDialog
      v-if="result"
      :open="reviewing"
      :book="null"
      :initial="initial"
      title="核对版权页书目"
      :submit="confirm"
      @close="reviewing = false"
      @saved="onSaved"
    >
      <template #before>
        <p class="muted small before">字段来自模型识别，请对照版权页逐项核对。简介不在版权页上，没有可靠来源就留空。</p>
      </template>
    </BookFormDialog>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 28px;
  align-items: start;
}
.drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 18px;
  border: 1.5px dashed var(--rule-strong);
  border-radius: var(--r-4);
  background: var(--surface);
  cursor: pointer;
  text-align: center;
}
.drop:focus-within {
  box-shadow: var(--focus);
}
.ph {
  display: grid;
  place-items: center;
  width: 100%;
  aspect-ratio: 3 / 4;
  border-radius: var(--r-2);
  background: var(--paper-2);
  color: var(--ink-4);
}
.ph svg {
  width: 32px;
  height: 32px;
}
.pv {
  width: 100%;
  border-radius: var(--r-2);
}
.status {
  display: flex;
  align-items: center;
  gap: 8px;
}
.status svg {
  width: 16px;
  height: 16px;
}
.err,
.exists {
  display: flex;
  gap: 10px;
  padding: 12px 14px;
  border-radius: var(--r-3);
  background: var(--danger-soft);
  margin-bottom: 14px;
}
.exists {
  background: var(--warn-soft);
}
.err > svg,
.exists > svg {
  width: 18px;
  height: 18px;
  flex: none;
  margin-top: 2px;
  color: var(--danger);
}
.exists > svg {
  color: var(--warn);
}
.ext {
  border: 1px solid var(--rule);
  border-radius: var(--r-3);
  background: var(--surface);
  overflow: hidden;
}
.ext caption {
  text-align: left;
  padding: 0 0 8px;
}
.ext th {
  position: static;
  width: 110px;
  background: var(--surface-sunk);
  font-size: var(--t-sm);
}
.notes {
  margin: 10px 0 0;
  padding-left: 18px;
  color: var(--warn);
}
.acts {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 16px;
}
.ok {
  margin-top: 12px;
  color: var(--ok);
}
.before {
  margin-bottom: 14px;
}
.idle {
  padding-top: 8px;
}
@media (max-width: 860px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
