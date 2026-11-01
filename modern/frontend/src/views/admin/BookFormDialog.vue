<script setup lang="ts">
// 出版版本表单：PATCH 提交全部可编辑字段。封面只能通过上传接口设置，不在这里。
import { reactive, ref, watch } from 'vue'
import { LoaderCircle } from 'lucide-vue-next'
import { books as bookApi } from '@/api/endpoints'
import type { BookInput, BookView } from '@/api/types'
import { fieldError } from '@/lib/forms'
import Modal from '@/components/Modal.vue'
import FormErrors from '@/components/FormErrors.vue'

const props = defineProps<{ open: boolean; book: BookView | null; initial?: Partial<BookInput> | null; title?: string; submit?: (input: BookInput) => Promise<BookView> }>()
const emit = defineEmits<{ close: []; saved: [b: BookView] }>()

const empty = (): BookInput => ({
  title: '', subtitle: '', authors: '', translators: '', isbn13: '', publisher: '', publicationDate: '',
  editionStatement: '', volume: '', language: 'zh', classificationNo: '', category: '', description: '', sourceUrl: '',
})
const form = reactive<BookInput>(empty())
const busy = ref(false)
const error = ref<unknown>(null)

watch(
  () => props.open,
  (o) => {
    if (!o) return
    error.value = null
    const b = props.book
    Object.assign(form, empty(), props.initial ?? {}, b ? pick(b) : {})
  },
)
function pick(b: BookView): BookInput {
  const keys = Object.keys(empty()) as (keyof BookInput)[]
  const out = Object.fromEntries(keys.map((k) => [k, (b as unknown as Record<string, string>)[k] ?? ''])) as unknown as BookInput
  out.workId = b.workId
  return out
}

async function save() {
  busy.value = true
  error.value = null
  try {
    const input: BookInput = { ...form, isbn13: form.isbn13.replace(/[-\s]/g, '') }
    const saved = props.submit
      ? await props.submit(input)
      : props.book
        ? await bookApi.update(props.book.id, input)
        : await bookApi.create(input)
    emit('saved', saved)
    emit('close')
  } catch (e) {
    error.value = e
  } finally {
    busy.value = false
  }
}
const fields: { key: keyof BookInput; label: string; req?: boolean; span?: boolean; mono?: boolean; hint?: string }[] = [
  { key: 'title', label: '书名', req: true, span: true },
  { key: 'subtitle', label: '副书名', span: true },
  { key: 'authors', label: '责任者', req: true },
  { key: 'translators', label: '译者' },
  { key: 'isbn13', label: 'ISBN-13', mono: true, hint: '校验位不正确会被拒绝' },
  { key: 'publisher', label: '出版社' },
  { key: 'publicationDate', label: '出版日期', hint: '按版权页原样填写' },
  { key: 'editionStatement', label: '版次' },
  { key: 'volume', label: '卷册' },
  { key: 'language', label: '语言' },
  { key: 'classificationNo', label: '分类号', mono: true },
  { key: 'category', label: '分类' },
  { key: 'sourceUrl', label: '书目来源网址', span: true, mono: true },
]
</script>

<template>
  <Modal :open="open" :title="title ?? (book ? '编辑出版版本' : '新增出版版本')" width="720px" @close="emit('close')">
    <slot name="before" />
    <form id="book-form" class="form-grid" novalidate @submit.prevent="save">
      <div v-for="f in fields" :key="f.key" class="field" :class="{ 'span-2': f.span }">
        <label :for="`bf-${f.key}`" :class="{ req: f.req }">{{ f.label }}</label>
        <input
          :id="`bf-${f.key}`"
          v-model="(form[f.key] as string)"
          class="input"
          :class="{ mono: f.mono }"
          autocomplete="off"
          :aria-invalid="!!fieldError(error, f.key)"
        />
        <span v-if="fieldError(error, f.key)" class="error-text">{{ fieldError(error, f.key) }}</span>
        <span v-else-if="f.hint" class="hint">{{ f.hint }}</span>
      </div>
      <div class="field span-2">
        <label for="bf-description">简介</label>
        <textarea id="bf-description" v-model="form.description" class="textarea" rows="4" placeholder="只填写有来源的简介，不要用生成内容代替"></textarea>
      </div>
      <div class="span-2"><FormErrors :error="error" /></div>
    </form>
    <template #foot>
      <button class="btn" type="button" @click="emit('close')">取消</button>
      <button class="btn btn-primary" type="submit" form="book-form" :disabled="busy"><LoaderCircle v-if="busy" class="spin" />保存</button>
    </template>
  </Modal>
</template>
