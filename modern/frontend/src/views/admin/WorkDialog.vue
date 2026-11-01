<script setup lang="ts">
// 人工作品分组：同一作品的不同出版版本归到一组；不由模型自动合并。
import { ref, watch } from 'vue'
import { Search, LoaderCircle } from 'lucide-vue-next'
import { books as bookApi } from '@/api/endpoints'
import { errorMessage } from '@/api/http'
import type { BookView } from '@/api/types'
import { toast } from '@/stores/toast'
import Modal from '@/components/Modal.vue'

const props = defineProps<{ open: boolean; book: BookView }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const q = ref('')
const results = ref<BookView[]>([])
const target = ref<BookView | null>(null)
const standalone = ref(false)
const reason = ref('')
const busy = ref(false)
const error = ref('')

watch(
  () => props.open,
  (o) => {
    if (!o) return
    q.value = props.book.title.replace(/[（(].*$/, '').slice(0, 20)
    results.value = []
    target.value = null
    standalone.value = false
    reason.value = ''
    error.value = ''
    find()
  },
)
async function find() {
  if (!q.value.trim()) return
  try {
    const page = await bookApi.list({ q: q.value.trim(), size: 10 })
    results.value = page.items.filter((b) => b.id !== props.book.id)
  } catch (e) {
    error.value = errorMessage(e)
  }
}
async function save() {
  if (!standalone.value && !target.value) {
    error.value = '请选择要并入的版本，或选择单独成为一个作品'
    return
  }
  if (!reason.value.trim()) {
    error.value = '请填写原因'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await bookApi.assignWork(props.book.id, standalone.value ? null : target.value!.workId, reason.value.trim())
    toast('ok', '作品分组已更新')
    emit('saved')
    emit('close')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Modal :open="open" title="调整作品分组" width="560px" @close="emit('close')">
    <div class="wd">
      <p class="muted small">把《{{ book.title }}》并入另一个版本所在的作品。只合并同一作品的不同版本，不要合并不同的书。</p>
      <form class="row" role="search" @submit.prevent="find">
        <label class="searchbox grow">
          <Search aria-hidden="true" />
          <input v-model="q" class="input" placeholder="搜索要并入的版本" aria-label="搜索版本" />
        </label>
        <button class="btn" type="submit">搜索</button>
      </form>
      <ul class="pick" role="radiogroup" aria-label="目标版本">
        <li v-for="b in results" :key="b.id">
          <label :class="{ on: target?.id === b.id && !standalone }">
            <input type="radio" name="work-target" :checked="target?.id === b.id && !standalone" @change="() => { target = b; standalone = false }" />
            <span class="serif">{{ b.title }}</span>
            <span class="muted xs">{{ b.publisher }} · {{ b.editionStatement }} · 作品 {{ b.workId }}</span>
          </label>
        </li>
        <li v-if="!results.length" class="muted small">没有找到其它版本。</li>
        <li>
          <label :class="{ on: standalone }">
            <input v-model="standalone" type="checkbox" />
            <span>从当前分组拆出，单独成为一个作品</span>
          </label>
        </li>
      </ul>
      <div class="field">
        <label for="wd-reason" class="req">原因</label>
        <input id="wd-reason" v-model="reason" class="input" maxlength="200" placeholder="例如：同书第 2 版与第 3 版" />
      </div>
      <p v-if="error" class="error-text" role="alert">{{ error }}</p>
    </div>
    <template #foot>
      <button class="btn" type="button" @click="emit('close')">取消</button>
      <button class="btn btn-primary" type="button" :disabled="busy" @click="save"><LoaderCircle v-if="busy" class="spin" />保存分组</button>
    </template>
  </Modal>
</template>

<style scoped>
.wd { display: flex; flex-direction: column; gap: 14px; }
.row { display: flex; gap: 8px; }
.grow { flex: 1; width: auto; }
.pick { list-style: none; margin: 0; padding: 0; border: 1px solid var(--rule); border-radius: var(--r-3); max-height: 280px; overflow: auto; }
.pick li + li { border-top: 1px solid var(--rule); }
.pick label { display: grid; grid-template-columns: auto 1fr; gap: 2px 10px; padding: 10px 12px; cursor: pointer; }
.pick label .xs { grid-column: 2; }
.pick label.on { background: var(--accent-soft); }
.pick input { accent-color: var(--accent); margin-top: 4px; }
.pick li.muted { padding: 10px 12px; }
</style>
