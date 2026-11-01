<script setup lang="ts">
// 审计日志：关键变更的前后快照、操作人、原因和请求号。详情里只列出变化的字段。
import { computed, onMounted, ref } from 'vue'
import { ShieldCheck } from 'lucide-vue-next'
import { admin } from '@/api/endpoints'
import type { AuditView, Page } from '@/api/types'
import { fmtDateTime } from '@/lib/format'
import Pager from '@/components/Pager.vue'
import Modal from '@/components/Modal.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'

const OPS: Record<string, string> = {
  BORROW: '借出', RETURN: '归还', RENEW: '续借', CORRECT_LOAN: '更正借阅', CORRECT_RETURN: '更正归还',
  CANCEL_LOAN: '作废借阅', UNDO_RETURN: '撤销归还', COPY_CREATE: '新增副本', COPY_UPDATE: '修改副本',
  COPY_DELETE: '删除副本', COPY_OFF_SHELF: '副本下架', BOOK_CREATE: '新增版本', BOOK_UPDATE: '修改版本',
  BOOK_ARCHIVE: '归档版本', STUDENT_CREATE: '新增学生', STUDENT_UPDATE: '修改学生', STUDENT_DISABLE: '停用学生',
  STUDENT_DELETE: '删除学生', ACCOUNT_CREATE: '开通账号', COVER_UPLOAD: '上传封面', WORK_ASSIGN: '作品分组',
  IMPORT: '批量导入', KNOWLEDGE_CREATE: '新增资料', KNOWLEDGE_UPDATE: '修改资料', KNOWLEDGE_DISABLE: '停用资料',
}
const TARGETS: Record<string, string> = { LOAN: '借阅', COPY: '副本', BOOK: '版本', EDITION: '版本', STUDENT: '学生', ACCOUNT: '账号', KNOWLEDGE: '资料', WORK: '作品' }

// 快照字段的中文名；未列出的字段原样显示
const FIELDS: Record<string, string> = {
  accessionNo: '登录号', archived: '已归档', authors: '责任者', availableCount: '可借册数', borrowedAt: '借出时间',
  callNumber: '索书号', cancellationReason: '作废原因', category: '分类', circulationStatus: '流通状态',
  classificationNo: '分类号', college: '学院', content: '正文', copyCount: '副本数', copyId: '副本编号',
  coverUrl: '封面', currentBorrowedCount: '在借册数', description: '简介', documentType: '资料类型',
  dueAt: '应还时间', editionId: '版本编号', editionStatement: '版次', enabled: '启用', id: '编号', isbn13: 'ISBN',
  language: '语言', location: '位置', major: '专业', name: '姓名', note: '备注', overdue: '逾期',
  publicationDate: '出版日期', publisher: '出版社', renewCount: '续借次数', returnedAt: '归还时间', role: '角色',
  source: '来源', sourceRecordId: '来源记录号', sourceType: '来源类型', sourceUrl: '来源网址', status: '状态',
  studentId: '学生编号', studentName: '学生姓名', studentNo: '学号', subtitle: '副书名', title: '书名',
  translators: '译者', username: '用户名', version: '版本号', visibility: '可见范围', volume: '卷册', workId: '作品编号',
}
const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/

const page = ref(1)
const size = 30
const data = ref<Page<AuditView> | null>(null)
const loading = ref(false)
const error = ref<unknown>(null)
async function load() {
  loading.value = true
  error.value = null
  try {
    data.value = await admin.audit({ page: page.value, size })
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}
onMounted(load)

const open = ref<AuditView | null>(null)
function parse(s: string): Record<string, unknown> | null {
  if (!s) return null
  try {
    const v = JSON.parse(s)
    return v && typeof v === 'object' ? v : { value: v }
  } catch {
    return { raw: s }
  }
}
const diff = computed(() => {
  if (!open.value) return []
  const a = parse(open.value.beforeSnapshot) ?? {}
  const b = parse(open.value.afterSnapshot) ?? {}
  const keys = [...new Set([...Object.keys(a), ...Object.keys(b)])]
  const show = (v: unknown) => {
    if (v === null || v === undefined || v === '') return '—'
    if (typeof v === 'boolean') return v ? '是' : '否'
    if (typeof v === 'string' && ISO.test(v)) return fmtDateTime(v)
    return typeof v === 'object' ? JSON.stringify(v) : String(v)
  }
  return keys
    .map((k) => ({ k, before: show(a[k]), after: show(b[k]), changed: JSON.stringify(a[k]) !== JSON.stringify(b[k]) }))
    .sort((x, y) => Number(y.changed) - Number(x.changed))
})
const showAll = ref(false)
// 新增只有“之后”，删除 / 停用只有“之前”：这时按单份快照展示，不画删除线
const side = computed<'both' | 'before' | 'after'>(() => {
  if (!open.value) return 'both'
  const a = parse(open.value.beforeSnapshot)
  const b = parse(open.value.afterSnapshot)
  return a && b ? 'both' : a ? 'before' : 'after'
})
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">审计日志</h1>
        <p class="page-sub">借还、更正、作废和资料维护都会留下前后快照，对象删除后记录仍然保留。</p>
      </div>
    </header>
    <ErrorNotice v-if="error" :error="error" :retry="load" />
    <div v-else class="table-wrap">
      <SkeletonRows v-if="loading && !data" :rows="10" :cols="5" />
      <table v-else-if="data?.items.length" class="table">
        <thead><tr><th>时间</th><th>操作</th><th>对象</th><th>操作人</th><th>原因</th></tr></thead>
        <tbody>
          <tr v-for="a in data.items" :key="a.id" tabindex="0" @click="open = a; showAll = false" @keydown.enter="open = a; showAll = false">
            <td class="num small nowrap">{{ fmtDateTime(a.createdAt) }}</td>
            <td class="nowrap">{{ OPS[a.operationType] ?? a.operationType }}</td>
            <td class="small nowrap">{{ TARGETS[a.targetType] ?? a.targetType }} <span class="mono muted">#{{ a.targetId }}</span></td>
            <td class="small mono">#{{ a.operatorId }}</td>
            <td class="small reason"><span class="clamp-2">{{ a.reason || '—' }}</span></td>
          </tr>
        </tbody>
      </table>
      <EmptyState v-else :icon="ShieldCheck" title="还没有审计记录" />
    </div>
    <Pager v-if="data && data.totalElements > 0" :page="data.page" :total-pages="data.totalPages" :total="data.totalElements" :size="size" @go="(p) => { page = p; load() }" />

    <Modal :open="!!open" side :title="open ? `${OPS[open.operationType] ?? open.operationType} · ${TARGETS[open.targetType] ?? open.targetType} #${open.targetId}` : ''" width="640px" @close="open = null">
      <div v-if="open" class="detail">
        <dl class="dl small">
          <dt>时间</dt><dd class="num">{{ fmtDateTime(open.createdAt) }}</dd>
          <dt>操作人</dt><dd class="mono">#{{ open.operatorId }}</dd>
          <dt>原因</dt><dd>{{ open.reason || '—' }}</dd>
          <dt>请求号</dt><dd class="mono xs">{{ open.requestId }}</dd>
          <dt>原始代码</dt><dd class="mono xs">{{ open.operationType }}</dd>
        </dl>
        <div class="dh">
          <h4>{{ side === 'both' ? '字段变化' : side === 'before' ? '操作前的记录' : '新建的记录' }}</h4>
          <label v-if="side === 'both'" class="checkbox small"><input v-model="showAll" type="checkbox" />显示未变化的字段</label>
        </div>
        <div class="table-wrap">
          <table v-if="side !== 'both'" class="table diff">
            <thead><tr><th>字段</th><th>值</th></tr></thead>
            <tbody>
              <tr v-for="r in diff" :key="r.k">
                <td class="small" :title="r.k">{{ FIELDS[r.k] ?? r.k }}</td>
                <td class="small">{{ side === 'before' ? r.before : r.after }}</td>
              </tr>
            </tbody>
          </table>
          <table v-else class="table diff">
            <thead><tr><th>字段</th><th>之前</th><th>之后</th></tr></thead>
            <tbody>
              <tr v-for="r in diff.filter((r) => showAll || r.changed)" :key="r.k" :class="{ changed: r.changed }">
                <td class="small" :title="r.k">{{ FIELDS[r.k] ?? r.k }}</td>
                <td class="small b">{{ r.before }}</td>
                <td class="small a">{{ r.after }}</td>
              </tr>
              <tr v-if="!diff.some((r) => r.changed) && !showAll"><td colspan="3" class="muted small">前后快照相同。</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.reason { max-width: 360px; }
.detail { display: flex; flex-direction: column; gap: 18px; }
.dh { display: flex; align-items: center; justify-content: space-between; }
.diff td { overflow-wrap: anywhere; vertical-align: top; }
.diff tr.changed .b { color: var(--danger); text-decoration: line-through; text-decoration-color: color-mix(in srgb, var(--danger) 50%, transparent); }
.diff tr.changed .a { color: var(--ok); font-weight: 500; }
</style>
