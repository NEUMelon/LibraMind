<script setup lang="ts">
// 知识资料：只收录真实的简介、目录、规则等材料，供检索与问答引用；不能冒充图书全文。
// BOOK_METADATA 由后端根据书目自动维护，这里只读。
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, Search, BookOpenText, Pencil, Trash2, LoaderCircle } from 'lucide-vue-next'
import { knowledge as api } from '@/api/endpoints'
import type { DocumentView, KnowledgeInput } from '@/api/types'
import { fieldError } from '@/lib/forms'
import { toast } from '@/stores/toast'
import Modal from '@/components/Modal.vue'
import FormErrors from '@/components/FormErrors.vue'
import ReasonDialog from '@/components/ReasonDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import RowMenu from '@/components/RowMenu.vue'

const TYPES: Record<string, string> = {
  LIBRARY_POLICY: '借阅规则',
  DESCRIPTION: '图书简介',
  TABLE_OF_CONTENTS: '目录',
  PUBLIC_SUMMARY: '公开摘要',
  MANUAL_NOTE: '馆员笔记',
  BOOK_METADATA: '书目元数据',
}
const editableTypes = Object.entries(TYPES).filter(([k]) => k !== 'BOOK_METADATA')
const docs = ref<DocumentView[]>([])
const loading = ref(false)
const error = ref<unknown>(null)
const q = ref('')
const type = ref('')
const limit = ref(60)
// 后端列表只返回最近 100 条（不分页）；按关联版本编号查询时返回该版本的全部资料。
const editionFilter = ref('')
const capped = computed(() => !editionFilter.value && docs.value.length >= 100)

async function load() {
  loading.value = true
  error.value = null
  try {
    const id = Number(editionFilter.value)
    docs.value = await api.list(editionFilter.value && id > 0 ? id : undefined)
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}
onMounted(load)

const counts = computed(() => {
  const c: Record<string, number> = {}
  for (const d of docs.value) c[d.documentType] = (c[d.documentType] ?? 0) + 1
  return c
})
const filtered = computed(() => {
  const text = q.value.trim().toLowerCase()
  return docs.value.filter(
    (d) => (!type.value || d.documentType === type.value) && (!text || d.title.toLowerCase().includes(text) || d.content.toLowerCase().includes(text)),
  )
})

// ---------- 编辑 ----------
const editing = ref<DocumentView | null | 'new'>(null)
const form = reactive<KnowledgeInput>({ editionId: null, title: '', content: '', documentType: 'LIBRARY_POLICY', source: '', sourceUrl: '', visibility: 'PUBLIC' })
const saving = ref(false)
const formError = ref<unknown>(null)
function openEdit(d: DocumentView | 'new') {
  formError.value = null
  editing.value = d
  Object.assign(
    form,
    d === 'new'
      ? { editionId: null, title: '', content: '', documentType: 'LIBRARY_POLICY', source: '', sourceUrl: '', visibility: 'PUBLIC' }
      : { editionId: d.editionId, title: d.title, content: d.content, documentType: d.documentType, source: d.source, sourceUrl: d.sourceUrl, visibility: d.visibility },
  )
}
async function save() {
  saving.value = true
  formError.value = null
  try {
    const input = { ...form, editionId: form.editionId ? Number(form.editionId) : null }
    if (editing.value === 'new') await api.create(input)
    else if (editing.value) await api.update(editing.value.id, input)
    toast('ok', '资料已保存', '向量索引会在后台更新')
    editing.value = null
    load()
  } catch (e) {
    formError.value = e
  } finally {
    saving.value = false
  }
}
const removing = ref<DocumentView | null>(null)
async function remove(reason: string) {
  await api.remove(removing.value!.id, reason)
  toast('ok', '资料已停用')
  load()
}
const viewing = ref<DocumentView | null>(null)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">知识资料</h1>
        <p class="page-sub">检索和问答只引用这里的材料。没有导入的内容，助手会说明“资料不足”，不会编造。</p>
      </div>
      <button class="btn btn-primary" type="button" @click="openEdit('new')"><Plus />新增资料</button>
    </header>

    <div class="toolbar">
      <label class="searchbox">
        <Search aria-hidden="true" />
        <input v-model="q" class="input" type="search" placeholder="标题或正文" aria-label="搜索资料" />
      </label>
      <form class="ed-filter" @submit.prevent="load">
        <input v-model="editionFilter" class="input mono" inputmode="numeric" placeholder="版本编号" aria-label="按关联版本编号查询" />
        <button class="btn" type="submit">查询</button>
        <button v-if="editionFilter" class="btn btn-quiet" type="button" @click="() => { editionFilter = ''; load() }">清除</button>
      </form>
      <div class="chips" role="radiogroup" aria-label="资料类型">
        <button type="button" role="radio" :aria-checked="!type" :class="{ on: !type }" @click="type = ''">全部 <span class="num">{{ docs.length }}</span></button>
        <button v-for="(label, key) in TYPES" :key="key" type="button" role="radio" :aria-checked="type === key" :class="{ on: type === key }" @click="type = key">
          {{ label }} <span class="num">{{ counts[key] ?? 0 }}</span>
        </button>
      </div>
    </div>

    <p v-if="capped" class="xs muted cap">列表只含最近更新的 100 条，分类计数也只按这 100 条统计。要看某本书的全部资料，请按版本编号查询。</p>
    <ErrorNotice v-if="error" :error="error" :retry="load" />
    <div v-else class="table-wrap">
      <SkeletonRows v-if="loading && !docs.length" :rows="8" :cols="4" />
      <table v-else-if="filtered.length" class="table">
        <thead><tr><th>标题</th><th>类型</th><th>可见</th><th>关联版本</th><th class="col-num">版本号</th><th>状态</th><th class="col-actions"><span class="sr-only">操作</span></th></tr></thead>
        <tbody>
          <tr v-for="d in filtered.slice(0, limit)" :key="d.id" tabindex="0" @click="viewing = d" @keydown.enter="viewing = d">
            <td>
              <p class="dt">{{ d.title }}</p>
              <p class="xs muted clamp-2 excerpt">{{ d.content }}</p>
            </td>
            <td class="small nowrap">{{ TYPES[d.documentType] ?? d.documentType }}</td>
            <td class="small nowrap">{{ d.visibility === 'PUBLIC' ? '所有人' : '仅馆员' }}</td>
            <td class="small"><RouterLink v-if="d.editionId" :to="`/books?id=${d.editionId}`" @click.stop>#{{ d.editionId }}</RouterLink><span v-else class="muted">—</span></td>
            <td class="col-num small">v{{ d.version }}</td>
            <td><span class="badge" :class="d.enabled ? 'ok' : ''">{{ d.enabled ? '启用' : '停用' }}</span></td>
            <td class="col-actions">
              <RowMenu
                v-if="d.documentType !== 'BOOK_METADATA'"
                :items="[
                  { label: '编辑', icon: Pencil, run: () => openEdit(d) },
                  { label: '停用', icon: Trash2, danger: true, disabled: !d.enabled, run: () => (removing = d) },
                ]"
              />
              <span v-else class="xs muted" title="随书目自动更新">自动</span>
            </td>
          </tr>
        </tbody>
      </table>
      <EmptyState v-else :icon="BookOpenText" title="没有符合条件的资料" />
    </div>
    <div v-if="filtered.length > limit" class="more"><button class="btn" type="button" @click="limit += 100">再显示 100 条（共 {{ filtered.length }}）</button></div>

    <Modal :open="!!viewing" :title="viewing?.title ?? ''" side width="620px" @close="viewing = null">
      <div v-if="viewing" class="view">
        <p class="small muted">{{ TYPES[viewing.documentType] }} · {{ viewing.visibility === 'PUBLIC' ? '所有人可见' : '仅馆员' }} · v{{ viewing.version }}</p>
        <p v-if="viewing.source" class="small">来源：{{ viewing.source }}
          <a v-if="/^https?:\/\//.test(viewing.sourceUrl)" :href="viewing.sourceUrl" target="_blank" rel="noopener noreferrer">原文</a>
        </p>
        <pre class="body">{{ viewing.content }}</pre>
      </div>
    </Modal>

    <Modal :open="!!editing" :title="editing === 'new' ? '新增资料' : '编辑资料'" width="680px" @close="editing = null">
      <form id="kn-form" class="form-grid" novalidate @submit.prevent="save">
        <div class="field span-2">
          <label for="kn-title" class="req">标题</label>
          <input id="kn-title" v-model="form.title" class="input" maxlength="200" :aria-invalid="!!fieldError(formError, 'title')" />
        </div>
        <div class="field">
          <label for="kn-type" class="req">类型</label>
          <select id="kn-type" v-model="form.documentType" class="select">
            <option v-for="[key, label] in editableTypes" :key="key" :value="key">{{ label }}</option>
          </select>
        </div>
        <div class="field">
          <label for="kn-vis" class="req">可见范围</label>
          <select id="kn-vis" v-model="form.visibility" class="select">
            <option value="PUBLIC">所有人</option>
            <option value="ADMIN">仅馆员</option>
          </select>
        </div>
        <div class="field">
          <label for="kn-ed">关联版本编号</label>
          <input id="kn-ed" v-model="form.editionId" class="input mono" inputmode="numeric" placeholder="可选" />
          <span class="hint">简介和目录应关联到具体版本</span>
        </div>
        <div class="field">
          <label for="kn-src">来源</label>
          <input id="kn-src" v-model="form.source" class="input" placeholder="例如 出版社官网" />
        </div>
        <div class="field span-2">
          <label for="kn-url">来源网址</label>
          <input id="kn-url" v-model="form.sourceUrl" class="input mono" placeholder="https://" />
        </div>
        <div class="field span-2">
          <label for="kn-content" class="req">正文</label>
          <textarea id="kn-content" v-model="form.content" class="textarea" rows="10" :aria-invalid="!!fieldError(formError, 'content')"></textarea>
          <span class="hint">资料里的“指令”不会被执行，只作为引用文本。</span>
        </div>
        <div class="span-2"><FormErrors :error="formError" /></div>
      </form>
      <template #foot>
        <button class="btn" type="button" @click="editing = null">取消</button>
        <button class="btn btn-primary" type="submit" form="kn-form" :disabled="saving"><LoaderCircle v-if="saving" class="spin" />保存</button>
      </template>
    </Modal>
    <ReasonDialog :open="!!removing" title="停用资料" confirm-text="停用" :action="remove" @close="removing = null">
      停用后检索和问答不再引用《{{ removing?.title }}》，历史回答中的引用记录保留。
    </ReasonDialog>
  </div>
</template>

<style scoped>
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chips button {
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--rule);
  border-radius: 999px;
  background: var(--surface);
  color: var(--ink-2);
  font-size: var(--t-sm);
  cursor: pointer;
}
.chips button .num {
  color: var(--ink-4);
  margin-left: 2px;
}
.chips button.on {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent-text);
  font-weight: 600;
}
.dt {
  font-weight: 500;
}
.ed-filter {
  display: flex;
  gap: 6px;
}
.ed-filter .input {
  width: 110px;
}
.cap {
  margin: -4px 0 10px;
}
.excerpt {
  max-width: 520px;
  margin-top: 2px;
}
.more {
  display: flex;
  justify-content: center;
  padding: 14px;
}
.view {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.body {
  margin: 8px 0 0;
  white-space: pre-wrap;
  font-family: var(--font-ui);
  line-height: 1.8;
  overflow-wrap: anywhere;
}
</style>
