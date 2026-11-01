<script setup lang="ts">
// AI 与索引：向量索引状态和每次模型调用的费用估算（不是供应商账单）。
import { computed, onMounted, ref } from 'vue'
import { Cpu } from 'lucide-vue-next'
import { admin } from '@/api/endpoints'
import type { IndexStatus, Page, Usage } from '@/api/types'
import { fmtDateTime } from '@/lib/format'
import Pager from '@/components/Pager.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'

const index = ref<IndexStatus | null>(null)
const usage = ref<Page<Usage> | null>(null)
const page = ref(1)
const error = ref<unknown>(null)
async function load() {
  error.value = null
  try {
    ;[index.value, usage.value] = await Promise.all([admin.indexStatus(), admin.aiRequests({ page: page.value, size: 30 })])
  } catch (e) {
    error.value = e
  }
}
onMounted(load)
const TYPES: Record<string, string> = { AGENT: '助手对话', EMBEDDING: '向量', VISION: '识图', ASK: '资料问答', RAG: '资料问答' }
const pageCost = computed(() => usage.value?.items.reduce((s, u) => s + (u.estimatedUsd ?? 0), 0) ?? 0)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">AI 与索引</h1>
        <p class="page-sub">只使用固定的低价模型：对话与视觉 qwen3.5-flash，向量 text-embedding-v4（1024 维）。费用为本地估算，不是账单。</p>
      </div>
      <button class="btn" type="button" @click="load">刷新</button>
    </header>
    <ErrorNotice v-if="error" :error="error" :retry="load" />
    <template v-else>
      <section v-if="index" class="idx">
        <div class="idx-head">
          <h2 class="h">向量索引</h2>
          <span class="badge" :class="index.ready ? 'ok' : 'warn'">{{ index.ready ? '可用' : '未就绪，语义检索会降级为关键词' }}</span>
        </div>
        <dl class="grid">
          <div><dt>已载入片段</dt><dd class="num">{{ index.loaded }}</dd></div>
          <div><dt>待生成</dt><dd class="num">{{ index.pending }}</dd></div>
          <div :class="{ bad: index.failed }"><dt>失败</dt><dd class="num">{{ index.failed }}</dd></div>
          <div><dt>过期</dt><dd class="num">{{ index.stale }}</dd></div>
          <div><dt>后台生成</dt><dd>{{ index.workerEnabled ? '开启' : '关闭' }}</dd></div>
          <div><dt>模型键</dt><dd class="mono small">{{ index.modelKey }}</dd></div>
        </dl>
        <p v-if="!index.workerEnabled && index.pending" class="small muted note">后台生成已关闭，{{ index.pending }} 条片段还没有向量。开启需要在私有配置中设置 app.ai.index-worker-enabled=true，会产生少量费用。</p>
      </section>

      <section class="use">
        <div class="idx-head">
          <h2 class="h">模型调用</h2>
          <span class="small muted num">本页估算合计 US$ {{ pageCost.toFixed(4) }}</span>
        </div>
        <div class="table-wrap">
          <table v-if="usage?.items.length" class="table">
            <thead><tr><th>时间</th><th>用途</th><th>模型</th><th>结果</th><th class="col-num">输入 / 输出 token</th><th class="col-num">耗时</th><th class="col-num">估算 US$</th></tr></thead>
            <tbody>
              <tr v-for="u in usage.items" :key="u.id">
                <td class="num small nowrap">{{ fmtDateTime(u.createdAt) }}</td>
                <td class="small">{{ TYPES[u.type] ?? u.type }}</td>
                <td class="mono xs">{{ u.model }}</td>
                <td>
                  <span class="badge" :class="u.status === 'SUCCEEDED' || u.status === 'SUCCESS' ? 'ok' : u.status === 'FAILED' ? 'danger' : ''">{{ u.status }}</span>
                  <span v-if="u.errorCode" class="mono xs muted"> {{ u.errorCode }}</span>
                </td>
                <td class="col-num small">{{ u.inputTokens }} / {{ u.outputTokens }}</td>
                <td class="col-num small">{{ u.latencyMs }} ms</td>
                <td class="col-num small">{{ u.estimatedUsd == null ? '—' : u.estimatedUsd.toFixed(5) }}</td>
              </tr>
            </tbody>
          </table>
          <EmptyState v-else :icon="Cpu" title="还没有模型调用" compact />
        </div>
        <Pager v-if="usage && usage.totalElements > 0" :page="usage.page" :total-pages="usage.totalPages" :total="usage.totalElements" :size="30" @go="(p) => { page = p; load() }" />
      </section>
    </template>
  </div>
</template>

<style scoped>
.idx, .use { margin-bottom: 28px; }
.idx-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.h { font-size: var(--t-md); }
.grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); margin: 0; border: 1px solid var(--rule); border-radius: var(--r-3); background: var(--surface); }
.grid > div { padding: 12px 14px; min-width: 0; }
.grid > div + div { border-left: 1px solid var(--rule); }
.grid dt { font-size: var(--t-xs); color: var(--ink-3); }
.grid dd { margin: 4px 0 0; font-size: 18px; font-weight: 600; overflow-wrap: anywhere; }
.grid dd.small { font-size: var(--t-sm); font-weight: 400; }
.grid .bad dd { color: var(--danger); }
.note { margin-top: 8px; }
@media (max-width: 860px) {
  .grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .grid > div { border-left: 0 !important; border-top: 1px solid var(--rule); }
}
</style>
