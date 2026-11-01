<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search, Users, Plus } from 'lucide-vue-next'
import { students as studentApi } from '@/api/endpoints'
import type { Page, StudentView } from '@/api/types'
import { toast } from '@/stores/toast'
import Pager from '@/components/Pager.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import StudentFormDialog from './StudentFormDialog.vue'
import StudentDrawer from './StudentDrawer.vue'

const route = useRoute()
const router = useRouter()
const q = ref(String(route.query.q ?? ''))
const state = ref(String(route.query.state ?? ''))
const page = ref(Number(route.query.page ?? 1) || 1)
const openId = ref<number | null>(route.query.id ? Number(route.query.id) : null)
const size = 25
const data = ref<Page<StudentView> | null>(null)
const loading = ref(false)
const error = ref<unknown>(null)
const creating = ref(false)

async function load() {
  loading.value = true
  error.value = null
  try {
    data.value = await studentApi.list({ q: q.value.trim(), state: state.value || undefined, page: page.value, size })
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}
function sync() {
  router.replace({
    query: Object.fromEntries(
      Object.entries({ q: q.value, state: state.value, page: page.value > 1 ? String(page.value) : '', id: openId.value ? String(openId.value) : '' }).filter(([, v]) => v),
    ),
  })
}
let t = 0
watch(q, () => {
  clearTimeout(t)
  t = window.setTimeout(() => { page.value = 1; sync(); load() }, 280)
})
watch(state, () => { page.value = 1; sync(); load() })
function go(p: number) { page.value = p; sync(); load() }
function open(id: number | null) { openId.value = id; sync() }
function onCreated(s: StudentView) {
  toast('ok', `已新增学生 ${s.name}`)
  load()
  open(s.id)
}
onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">学生管理</h1>
        <p class="page-sub">已借数量按在借记录实时汇总。有借阅历史的学生删除时会改为停用。</p>
      </div>
      <button class="btn btn-primary" type="button" @click="creating = true"><Plus />新增学生</button>
    </header>
    <div class="toolbar">
      <label class="searchbox">
        <Search aria-hidden="true" />
        <input v-model="q" class="input" type="search" placeholder="学号、姓名、学院或专业" aria-label="搜索学生" />
      </label>
      <select v-model="state" class="select w-auto" aria-label="状态">
        <option value="">全部状态</option>
        <option value="enabled">启用</option>
        <option value="disabled">停用</option>
      </select>
    </div>
    <ErrorNotice v-if="error" :error="error" :retry="load" />
    <div v-else class="table-wrap">
      <SkeletonRows v-if="loading && !data" :rows="10" />
      <table v-else-if="data?.items.length" class="table" :class="{ dim: loading }">
        <thead><tr><th>学号</th><th>姓名</th><th>学院</th><th>专业</th><th class="col-num">在借</th><th>状态</th></tr></thead>
        <tbody>
          <tr
            v-for="s in data.items"
            :key="s.id"
            tabindex="0"
            :class="{ 'is-selected': openId === s.id }"
            @click="open(s.id)"
            @keydown.enter="open(s.id)"
          >
            <td class="mono">{{ s.studentNo }}</td>
            <td>{{ s.name }}</td>
            <td class="small ink-2">{{ s.college }}</td>
            <td class="small ink-2">{{ s.major }}</td>
            <td class="col-num"><span :class="{ muted: !s.currentBorrowedCount }">{{ s.currentBorrowedCount }}</span></td>
            <td><span class="badge" :class="s.enabled ? 'ok' : ''">{{ s.enabled ? '启用' : '停用' }}</span></td>
          </tr>
        </tbody>
      </table>
      <EmptyState v-else :icon="Users" :title="q ? `没有找到“${q}”` : '没有符合条件的学生'" />
    </div>
    <Pager v-if="data && data.totalElements > 0" :page="data.page" :total-pages="data.totalPages" :total="data.totalElements" :size="size" @go="go" />

    <StudentFormDialog :open="creating" :student="null" @close="creating = false" @saved="onCreated" />
    <StudentDrawer :id="openId" @close="open(null)" @changed="load" />
  </div>
</template>

<style scoped>
.w-auto { width: auto; }
.dim { opacity: 0.6; }
</style>
