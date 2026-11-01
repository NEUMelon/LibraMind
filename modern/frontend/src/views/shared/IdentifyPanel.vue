<script setup lang="ts">
// 识书：条码（ISBN EAN-13 / 馆藏 CODE128）在后端本地解码；封面识别调用视觉模型，只提取看得见的字段。
// 库存始终来自数据库实时查询。原图默认不保存。
import { computed, onBeforeUnmount, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { Camera, ImageUp, ScanBarcode, BookImage, LoaderCircle, CircleAlert, Check } from 'lucide-vue-next'
import { vision } from '@/api/endpoints'
import { ApiError, errorMessage } from '@/api/http'
import type { BarcodeResult, BookView, VisionResult } from '@/api/types'
import { copyStatus, errorHints } from '@/lib/format'
import { toast } from '@/stores/toast'
import BookCover from '@/components/BookCover.vue'

const props = defineProps<{ bookLink: (editionId: number) => string; compact?: boolean }>()

type Mode = 'barcode' | 'cover'
const mode = ref<Mode>('barcode')
const preview = ref('')
const busy = ref(false)
const error = ref<{ message: string; code: string } | null>(null)
const barcode = ref<BarcodeResult | null>(null)
const cover = ref<VisionResult | null>(null)
const chosen = ref<number | null | undefined>(undefined)
const dragging = ref(false)

function reset() {
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = ''
  error.value = null
  barcode.value = null
  cover.value = null
  chosen.value = undefined
}
function setMode(m: Mode) {
  mode.value = m
  reset()
}

async function handle(file: File | undefined) {
  if (!file) return
  reset()
  if (!/^image\/(jpeg|png|webp)$/.test(file.type)) {
    error.value = { message: '只支持 JPEG、PNG 或 WebP 图片', code: '' }
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    error.value = { message: '图片超过 10MB，请压缩或重新拍摄', code: 'IMAGE_TOO_LARGE' }
    return
  }
  preview.value = URL.createObjectURL(file)
  busy.value = true
  try {
    if (mode.value === 'barcode') barcode.value = await vision.barcode(file)
    else cover.value = await vision.cover(file)
  } catch (e) {
    const code = e instanceof ApiError ? e.code : ''
    error.value = { message: errorHints[code] ? `${errorHints[code]}。${code === 'AI_UNAVAILABLE' ? '可以改用条码识别或直接检索。' : ''}` : errorMessage(e), code }
  } finally {
    busy.value = false
  }
}
function onPick(e: Event) {
  const input = e.target as HTMLInputElement
  handle(input.files?.[0])
  input.value = ''
}
function onDrop(e: DragEvent) {
  dragging.value = false
  handle(e.dataTransfer?.files?.[0])
}

const editions = computed<BookView[]>(() => cover.value?.candidates.flatMap((w) => w.editions) ?? [])
const outcomeText: Record<string, string> = {
  EXACT_EDITION: '封面上的 ISBN 与馆藏版本完全一致',
  SINGLE_EDITION: '找到一个可能的版本，请核对',
  MULTIPLE_EDITIONS: '同一作品有多个版本，请按版次选择',
  MULTIPLE_WORKS: '有几本书比较像，请选择',
  NOT_FOUND: '馆内没有找到匹配的书',
}

async function choose(editionId: number | null) {
  if (!cover.value) return
  try {
    await vision.choose(cover.value.recognitionId, editionId)
    chosen.value = editionId
    if (editionId === null) toast('info', '已记录：候选里没有这本书')
  } catch (e) {
    toast('danger', '没有保存选择', errorMessage(e))
  }
}
onBeforeUnmount(reset)
</script>

<template>
  <div class="idf" :class="{ compact }">
    <div class="seg" role="tablist" aria-label="识别方式">
      <button role="tab" type="button" :aria-selected="mode === 'barcode'" :class="{ on: mode === 'barcode' }" @click="setMode('barcode')"><ScanBarcode />条码</button>
      <button role="tab" type="button" :aria-selected="mode === 'cover'" :class="{ on: mode === 'cover' }" @click="setMode('cover')"><BookImage />封面</button>
    </div>
    <p class="muted small lead">
      {{ mode === 'barcode' ? '拍下书后的 ISBN 条码，或贴在书上的馆藏登录号条码。条码在服务器本地识别，不调用模型。' : '拍下封面，视觉模型只读取看得见的书名、作者和 ISBN，再与馆藏比对。' }}
    </p>

    <div
      class="drop"
      :class="{ over: dragging, has: !!preview }"
      @dragover.prevent="dragging = true"
      @dragleave="dragging = false"
      @drop.prevent="onDrop"
    >
      <img v-if="preview" :src="preview" alt="待识别的图片" class="pv" />
      <div class="pick">
        <label class="btn btn-primary btn-lg cam">
          <Camera />拍照
          <input type="file" accept="image/jpeg,image/png,image/webp" capture="environment" class="sr-only" @change="onPick" />
        </label>
        <label class="btn btn-lg">
          <ImageUp />选择图片
          <input type="file" accept="image/jpeg,image/png,image/webp" class="sr-only" @change="onPick" />
        </label>
      </div>
      <p class="xs muted">JPEG / PNG / WebP，10MB 以内。电脑上也可以把图片拖到这里。</p>
    </div>

    <p v-if="busy" class="status muted" role="status"><LoaderCircle class="spin" />正在识别…</p>
    <div v-if="error" class="err" role="alert">
      <CircleAlert aria-hidden="true" />
      <div>
        <p>{{ error.message }}</p>
        <p v-if="error.code" class="mono xs muted">{{ error.code }}</p>
      </div>
    </div>

    <!-- 条码结果 -->
    <section v-if="barcode" class="res" aria-live="polite">
      <template v-if="barcode.found && (barcode.copy || barcode.book)">
        <p class="kind small muted">
          识别到{{ barcode.copy ? '馆藏登录号' : 'ISBN' }} <span class="mono">{{ barcode.value }}</span>
        </p>
        <RouterLink v-if="barcode.book" :to="bookLink(barcode.book.id)" class="hit">
          <BookCover :title="barcode.book.title" :authors="barcode.book.authors" :cover-url="barcode.book.coverUrl" :seed="barcode.book.isbn13" :width="64" />
          <div>
            <p class="serif t">{{ barcode.book.title }}</p>
            <p class="small ink-2">{{ barcode.book.authors }}</p>
            <p class="small">
              <template v-if="barcode.copy">
                这一册：<span class="badge" :class="copyStatus[barcode.copy.circulationStatus].tone">{{ copyStatus[barcode.copy.circulationStatus].label }}</span>
                <span class="muted"> {{ barcode.copy.location }}</span>
              </template>
              <template v-else>
                <span class="badge" :class="barcode.book.availableCount ? 'ok' : 'warn'">{{ barcode.book.availableCount ? `${barcode.book.availableCount} 册可借` : '全部借出' }}</span>
              </template>
            </p>
          </div>
        </RouterLink>
      </template>
      <p v-else-if="barcode.value" class="none">识别到 <span class="mono">{{ barcode.value }}</span>（{{ barcode.format }}），但馆内没有对应的书或副本。</p>
      <p v-else class="none">没有在图片里找到条码。请让条码充满画面、保持平整后重拍。</p>
    </section>

    <!-- 封面结果 -->
    <section v-if="cover" class="res" aria-live="polite">
      <p class="kind small"><b>{{ outcomeText[cover.outcome] ?? cover.outcome }}</b></p>
      <dl v-if="cover.extracted" class="dl small ext">
        <dt>读到的书名</dt><dd>{{ cover.extracted.title || '—' }}</dd>
        <dt>作者</dt><dd>{{ cover.extracted.authors || '—' }}</dd>
        <dt>ISBN</dt><dd class="mono">{{ cover.extracted.isbn13 || '未看到或校验不通过' }}</dd>
      </dl>
      <ul v-if="cover.warnings?.length" class="warns xs">
        <li v-for="w in cover.warnings" :key="w">{{ w }}</li>
      </ul>
      <ul v-if="editions.length" class="cands">
        <li v-for="e in editions" :key="e.id" :class="{ picked: chosen === e.id }">
          <BookCover :title="e.title" :authors="e.authors" :cover-url="e.coverUrl" :seed="e.isbn13" :width="48" />
          <div class="cb">
            <p class="serif">{{ e.title }}</p>
            <p class="xs muted">{{ e.publisher }} · {{ e.editionStatement || '版次未注明' }} · {{ e.availableCount }}/{{ e.copyCount }} 可借</p>
          </div>
          <RouterLink v-if="chosen === e.id" :to="bookLink(e.id)" class="btn btn-sm btn-primary">查看<Check /></RouterLink>
          <button v-else class="btn btn-sm" type="button" :disabled="chosen !== undefined" @click="choose(e.id)">是这本</button>
        </li>
      </ul>
      <button v-if="editions.length && chosen === undefined" class="link-btn small" type="button" @click="choose(null)">都不是</button>
      <p class="xs muted model">由 {{ cover.model }} 识别；识别结果仅用于找书，不代表读过整本书。</p>
    </section>
  </div>
</template>

<style scoped>
.idf {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 640px;
}
.seg {
  display: inline-flex;
  align-self: flex-start;
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
  padding: 0 14px;
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
.drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 24px 16px;
  border: 1.5px dashed var(--rule-strong);
  border-radius: var(--r-4);
  background: var(--surface);
  text-align: center;
}
.drop.over {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.pv {
  max-height: 240px;
  width: auto;
  border-radius: var(--r-2);
  box-shadow: var(--shadow-1);
}
.pick {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: center;
}
.pick label {
  cursor: pointer;
}
.cam {
  display: none;
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
.err {
  display: flex;
  gap: 10px;
  padding: 12px 14px;
  border-radius: var(--r-3);
  background: var(--danger-soft);
}
.err > svg {
  width: 18px;
  height: 18px;
  color: var(--danger);
  flex: none;
  margin-top: 2px;
}
.res {
  padding: 16px;
  border: 1px solid var(--rule);
  border-radius: var(--r-4);
  background: var(--surface);
}
.kind {
  margin-bottom: 10px;
}
.hit {
  display: flex;
  gap: 14px;
  color: inherit;
  text-decoration: none !important;
}
.hit .t {
  font-size: 17px;
  font-weight: 600;
}
.hit p + p {
  margin-top: 4px;
}
.none {
  color: var(--ink-2);
}
.ext {
  margin-bottom: 12px;
}
.warns {
  margin: 0 0 10px;
  padding-left: 18px;
  color: var(--warn);
}
.cands {
  list-style: none;
  margin: 0 0 8px;
  padding: 0;
}
.cands li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid var(--rule);
}
.cands li.picked {
  background: var(--accent-soft);
  margin: 0 -16px;
  padding: 10px 16px;
}
.cb {
  flex: 1;
  min-width: 0;
}
.model {
  margin-top: 10px;
}
@media (pointer: coarse) {
  .cam {
    display: inline-flex;
  }
}
</style>
