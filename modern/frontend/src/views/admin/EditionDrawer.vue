<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ImageUp, ExternalLink, Barcode, LoaderCircle, Pencil, Trash2, Plus, Link2 } from 'lucide-vue-next'
import { books as bookApi, copies as copyApi } from '@/api/endpoints'
import { errorMessage } from '@/api/http'
import type { BookView, CopyView, WorkView } from '@/api/types'
import { copyStatus } from '@/lib/format'
import { toast } from '@/stores/toast'
import Modal from '@/components/Modal.vue'
import BookCover from '@/components/BookCover.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import ReasonDialog from '@/components/ReasonDialog.vue'
import BookFormDialog from './BookFormDialog.vue'
import CopyFormDialog from './CopyFormDialog.vue'
import WorkDialog from './WorkDialog.vue'

const props = defineProps<{ id: number | null }>()
const emit = defineEmits<{ close: []; changed: [book: BookView | null] }>()

const book = ref<BookView | null>(null)
const copies = ref<CopyView[]>([])
const work = ref<WorkView | null>(null)
const loading = ref(false)
const error = ref<unknown>(null)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement>()

async function load(id: number) {
  loading.value = true
  error.value = null
  try {
    const [b, c] = await Promise.all([bookApi.get(id), bookApi.copies(id)])
    book.value = b
    copies.value = c.items
    work.value = b.workId ? await bookApi.work(b.workId).catch(() => null) : null
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}
watch(() => props.id, (id) => id && load(id), { immediate: true })

async function refresh() {
  if (!book.value) return
  await load(book.value.id)
  emit('changed', book.value)
}

const otherEditions = computed(() => work.value?.editions.filter((e) => e.id !== book.value?.id) ?? [])

async function upload(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file || !book.value) return
  uploading.value = true
  try {
    await bookApi.uploadCover(book.value.id, file)
    await refresh()
    toast('ok', '封面已更新')
  } catch (err) {
    toast('danger', '封面上传失败', errorMessage(err))
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

// ---------- 编辑与删除 ----------
const editing = ref(false)
const removing = ref(false)
async function removeEdition(reason: string) {
  await bookApi.remove(book.value!.id, reason)
  toast('ok', '版本已归档')
  emit('changed', null)
  emit('close')
}

const copyEditing = ref<{ copy: CopyView | null } | null>(null)
const copyRemoving = ref<CopyView | null>(null)
async function removeCopy(reason: string) {
  const r = await copyApi.remove(copyRemoving.value!.id, reason)
  toast('ok', r === 'OFF_SHELF' ? '有流通历史，已改为下架' : '副本已删除')
  await refresh()
}
const nextIndex = computed(() => {
  const nums = copies.value.map((c) => Number(c.accessionNo.match(/-(\d+)$/)?.[1] ?? 0))
  return (nums.length ? Math.max(...nums) : 0) + 1
})
const grouping = ref(false)

const fields = computed(() => {
  const b = book.value
  if (!b) return []
  return [
    ['ISBN', b.isbn13 || '—', 'mono'],
    ['出版社', b.publisher || '—', ''],
    ['出版日期', b.publicationDate || '未注明', ''],
    ['版次', b.editionStatement || '未注明', ''],
    ['卷册', b.volume || '—', ''],
    ['译者', b.translators || '—', ''],
    ['分类', b.category || '—', ''],
    ['分类号', b.classificationNo || '—', 'mono'],
    ['语言', b.language || '—', ''],
    ['作品编号', b.workId ?? '—', 'mono'],
  ] as const
})
</script>

<template>
  <Modal :open="id !== null" side title="出版版本" width="700px" @close="emit('close')">
    <template #head-extra>
      <div v-if="book" class="head-acts">
        <button class="btn btn-sm" type="button" @click="editing = true"><Pencil />编辑</button>
        <button class="btn btn-sm btn-danger btn-icon" type="button" aria-label="删除版本" title="删除版本" @click="removing = true"><Trash2 /></button>
      </div>
    </template>
    <SkeletonRows v-if="loading && !book" :rows="6" :cols="3" />
    <ErrorNotice v-else-if="error" :error="error" :retry="() => id && load(id)" />
    <div v-else-if="book" class="ed">
      <div class="hero">
        <div class="cover-col">
          <BookCover :title="book.title" :authors="book.authors" :cover-url="book.coverUrl" :seed="book.isbn13" :width="132" />
          <label class="btn btn-sm upload">
            <LoaderCircle v-if="uploading" class="spin" /><ImageUp v-else />{{ uploading ? '上传中' : '上传封面' }}
            <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" class="sr-only" @change="upload" />
          </label>
          <span class="xs muted">JPEG / PNG / WebP，10MB 内</span>
        </div>
        <div class="info">
          <h3 class="serif t">{{ book.title }}</h3>
          <p v-if="book.subtitle" class="serif st">{{ book.subtitle }}</p>
          <p class="au">{{ book.authors }}</p>
          <p class="stock">
            <span class="badge" :class="book.availableCount > 0 ? 'ok' : 'warn'">
              {{ book.availableCount > 0 ? `${book.availableCount} 册可借` : '暂无可借' }}
            </span>
            <span class="muted small">共 {{ book.copyCount }} 册副本</span>
            <span v-if="book.archived" class="badge">已归档</span>
          </p>
          <dl class="dl small">
            <template v-for="[k, v, cls] in fields" :key="k">
              <dt>{{ k }}</dt>
              <dd :class="cls">{{ v }}</dd>
            </template>
          </dl>
        </div>
      </div>

      <p v-if="book.description" class="desc">{{ book.description }}</p>
      <p v-else class="desc muted small">书目来源未提供简介。</p>
      <p class="src small muted">
        来源：{{ book.sourceType === 'IMPORT' ? '批量导入' : book.sourceType === 'MANUAL' ? '手工录入' : book.sourceType }}
        <a v-if="/^https?:\/\//.test(book.sourceUrl ?? '')" :href="book.sourceUrl" target="_blank" rel="noopener noreferrer">
          原始书目 <ExternalLink class="ext" />
        </a>
      </p>

      <section class="sec">
        <div class="sec-head">
          <h4>实体副本 <span class="muted num">{{ copies.length }}</span></h4>
          <button class="btn btn-sm" type="button" @click="copyEditing = { copy: null }"><Plus />新增副本</button>
        </div>
        <div class="table-wrap">
          <table class="table">
            <thead><tr><th>馆藏登录号</th><th>位置</th><th>索书号</th><th>状态</th><th class="col-actions"><span class="sr-only">操作</span></th></tr></thead>
            <tbody>
              <tr v-for="c in copies" :key="c.id" :class="{ archived: c.archived }">
                <td class="mono nowrap">{{ c.accessionNo }}</td>
                <td class="small">{{ c.location || '—' }}</td>
                <td class="mono small">{{ c.callNumber || '—' }}</td>
                <td><span class="badge" :class="copyStatus[c.circulationStatus].tone">{{ copyStatus[c.circulationStatus].label }}</span></td>
                <td class="col-actions">
                  <a class="btn btn-sm btn-quiet btn-icon" :href="copyApi.barcodeUrl(c.id)" target="_blank" rel="noopener" :aria-label="`${c.accessionNo} 条码`" title="条码图片"><Barcode /></a>
                  <button class="btn btn-sm btn-quiet btn-icon" type="button" :aria-label="`编辑 ${c.accessionNo}`" title="编辑" @click="copyEditing = { copy: c }"><Pencil /></button>
                  <button class="btn btn-sm btn-quiet btn-icon" type="button" :aria-label="`删除 ${c.accessionNo}`" title="删除或下架" :disabled="c.circulationStatus === 'BORROWED'" @click="copyRemoving = c"><Trash2 /></button>
                </td>
              </tr>
              <tr v-if="!copies.length"><td colspan="5" class="muted small">这个版本还没有实体副本。</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="sec">
        <div class="sec-head">
          <h4>作品分组</h4>
          <button class="btn btn-sm" type="button" @click="grouping = true"><Link2 />调整分组</button>
        </div>
        <p v-if="!otherEditions.length" class="muted small">这个版本单独成为一个作品。同一作品的不同版本（例如第 2 版和第 3 版）可以人工归到一组。</p>
        <ul v-else class="others">
          <li v-for="e in otherEditions" :key="e.id">
            <span class="serif">{{ e.title }}</span>
            <span class="muted small">{{ e.publisher }} · {{ e.editionStatement || '版次未注明' }} · {{ e.availableCount }}/{{ e.copyCount }} 可借</span>
          </li>
        </ul>
      </section>
    </div>

    <BookFormDialog :open="editing" :book="book" @close="editing = false" @saved="() => { refresh(); toast('ok', '书目已保存') }" />
    <ReasonDialog :open="removing" title="删除出版版本" confirm-text="归档" :action="removeEdition" @close="removing = false">
      版本下还有副本时后端会拒绝删除，请先处理副本。归档后不再出现在检索中，历史借阅记录保留。
    </ReasonDialog>
    <CopyFormDialog
      v-if="book"
      :open="!!copyEditing"
      :copy="copyEditing?.copy ?? null"
      :edition-id="book.id"
      :isbn="book.isbn13"
      :next-index="nextIndex"
      @close="copyEditing = null"
      @saved="() => { refresh(); toast('ok', '副本已保存') }"
    />
    <ReasonDialog :open="!!copyRemoving" title="删除副本" confirm-text="提交" :action="removeCopy" @close="copyRemoving = null">
      <span class="mono">{{ copyRemoving?.accessionNo }}</span>：有流通历史的副本会改为下架并保留记录，没有历史的会被删除。
    </ReasonDialog>
    <WorkDialog v-if="book" :open="grouping" :book="book" @close="grouping = false" @saved="refresh" />
  </Modal>
</template>

<style scoped>
.head-acts {
  display: flex;
  gap: 6px;
}
.hero {
  display: flex;
  gap: 22px;
}
.cover-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  width: 140px;
  text-align: center;
}
.upload {
  cursor: pointer;
}
.info {
  min-width: 0;
  flex: 1;
}
.t {
  font-size: 22px;
  line-height: 1.35;
}
.st {
  margin-top: 2px;
  color: var(--ink-2);
  font-size: var(--t-lg);
}
.au {
  margin-top: 6px;
  color: var(--ink-2);
}
.stock {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 12px 0 14px;
}
.desc {
  margin-top: 18px;
  line-height: 1.8;
  white-space: pre-line;
}
.src {
  margin-top: 8px;
}
.src a {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: 6px;
}
.ext {
  width: 12px;
  height: 12px;
}
.sec {
  margin-top: 26px;
}
.sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.sec-head h4 {
  font-size: var(--t-md);
}
tr.archived td {
  color: var(--ink-4);
}
.others {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.others li {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  align-items: baseline;
}
@media (max-width: 560px) {
  .hero {
    flex-direction: column;
  }
}
</style>
