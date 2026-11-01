<script setup lang="ts">
// 操作确认卡：AI 或页面只“准备”，最终执行必须由用户点击按钮走普通 REST。
// 网络不明时以 GET 查询真实状态；到期自动停用并刷新。
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BookCheck, BookUp, RefreshCw, Clock, LoaderCircle } from 'lucide-vue-next'
import { ai } from '@/api/endpoints'
import { ApiError, errorMessage } from '@/api/http'
import type { ActionView } from '@/api/types'
import { actionStatus, actionType, errorHints, fmtDate } from '@/lib/format'

const props = defineProps<{ action: ActionView }>()
const emit = defineEmits<{ updated: [action: ActionView] }>()

const current = ref<ActionView>(props.action)
watch(() => props.action, (a) => (current.value = a))
const busy = ref<'' | 'confirm' | 'cancel' | 'check'>('')
const problem = ref('')
const now = ref(Date.now())
let timer = 0

const expired = computed(() => new Date(current.value.expiresAt).getTime() <= now.value)
const pending = computed(() => current.value.status === 'PENDING')
const canAct = computed(() => pending.value && !expired.value && !busy.value)
const remaining = computed(() => {
  const s = Math.max(0, Math.round((new Date(current.value.expiresAt).getTime() - now.value) / 1000))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
})
const icon = computed(() => ({ BORROW: BookUp, RENEW: RefreshCw, RETURN: BookCheck })[current.value.type])
const status = computed(() => actionStatus[current.value.status] ?? { label: current.value.status, tone: '' })
const resultLoan = computed(() => {
  const r = current.value.result as { dueAt?: string; loans?: { dueAt: string }[] } | null
  return r?.dueAt ?? r?.loans?.[0]?.dueAt ?? null
})

function set(a: ActionView) {
  current.value = a
  emit('updated', a)
}

async function refresh() {
  busy.value = 'check'
  try {
    set(await ai.action(current.value.id))
    problem.value = ''
  } catch (e) {
    problem.value = errorMessage(e)
  } finally {
    busy.value = ''
  }
}

async function run(kind: 'confirm' | 'cancel') {
  busy.value = kind
  problem.value = ''
  try {
    set(await (kind === 'confirm' ? ai.confirm(current.value.id) : ai.cancel(current.value.id)))
  } catch (e) {
    if (e instanceof ApiError && (e.uncertain || e.status >= 500 || e.status === 0)) {
      // 结果不明：不能说“失败”，查询真实状态。
      problem.value = '网络不稳定，正在核对真实结果…'
      busy.value = ''
      await refresh()
      return
    }
    problem.value = e instanceof ApiError ? errorHints[e.code] ?? e.message : errorMessage(e)
    await refresh()
  } finally {
    busy.value = ''
  }
}

onMounted(() => {
  timer = window.setInterval(() => {
    now.value = Date.now()
    if (pending.value && expired.value && busy.value === '') {
      // 到期：停用按钮并以服务端状态为准。
      refresh()
    }
  }, 1000)
})
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <section class="card" :class="[`s-${current.status.toLowerCase()}`]" :aria-label="`${actionType[current.type].title}确认卡`">
    <header class="top">
      <span class="kind"><component :is="icon" aria-hidden="true" />{{ actionType[current.type].title }}</span>
      <span class="badge" :class="status.tone">{{ status.label }}</span>
      <span v-if="pending && !expired" class="ttl num" title="确认有效期"><Clock aria-hidden="true" />{{ remaining }}</span>
    </header>
    <div class="what">
      <p class="t serif">{{ current.preview.title }}</p>
      <dl class="meta">
        <div><dt>登录号</dt><dd class="mono">{{ current.preview.accessionNo }}</dd></div>
        <div v-if="current.preview.expectedDueAt">
          <dt>原应还</dt>
          <dd class="num">{{ fmtDate(current.preview.expectedDueAt) }}</dd>
        </div>
        <div v-if="current.status === 'SUCCEEDED' && resultLoan && current.type !== 'RETURN'"><dt>现应还</dt><dd class="num">{{ fmtDate(resultLoan) }}</dd></div>
      </dl>
    </div>
    <p v-if="current.status === 'FAILED'" class="note danger">
      未执行：{{ errorHints[current.failureCode ?? ''] ?? current.failureCode }}。请重新准备，不会用旧内容重试。
    </p>
    <p v-else-if="current.status === 'EXPIRED'" class="note">已超过有效期，未执行任何操作。</p>
    <p v-else-if="current.status === 'CANCELLED'" class="note">已取消，未执行任何操作。</p>
    <p v-else-if="current.status === 'SUCCEEDED'" class="note ok">已完成。借阅记录以“我的书架”为准。</p>
    <p v-if="problem" class="note warn" role="status">{{ problem }}</p>
    <footer v-if="pending" class="acts">
      <button class="btn btn-primary" type="button" :disabled="!canAct" @click="run('confirm')">
        <LoaderCircle v-if="busy === 'confirm'" class="spin" />{{ actionType[current.type].verb }}
      </button>
      <button class="btn btn-quiet" type="button" :disabled="!canAct" @click="run('cancel')">取消</button>
    </footer>
  </section>
</template>

<style scoped>
.card {
  border: 1px solid var(--rule-strong);
  border-radius: var(--r-3);
  background: var(--surface);
  padding: 12px 14px 14px;
  max-width: 420px;
}
.card.s-pending {
  border-color: color-mix(in srgb, var(--accent) 45%, var(--rule));
  box-shadow: 0 0 0 3px var(--accent-soft);
}
.top {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--t-sm);
}
.kind {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: var(--ink-2);
}
.kind svg, .ttl svg {
  width: 15px;
  height: 15px;
}
.ttl {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ink-3);
  font-size: var(--t-xs);
}
.what {
  margin-top: 10px;
}
.t {
  font-size: var(--t-lg);
  line-height: 1.4;
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 18px;
  margin: 6px 0 0;
  font-size: var(--t-sm);
}
.meta div { display: flex; gap: 6px; }
.meta dt { color: var(--ink-3); }
.meta dd { margin: 0; }
.note {
  margin-top: 10px;
  font-size: var(--t-sm);
  color: var(--ink-3);
}
.note.danger { color: var(--danger); }
.note.ok { color: var(--ok); }
.note.warn { color: var(--warn); }
.acts {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.spin { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
