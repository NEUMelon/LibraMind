<script setup lang="ts">
import { computed } from 'vue'
import { CircleAlert, RotateCw } from 'lucide-vue-next'
import { ApiError } from '@/api/http'

const props = defineProps<{ error: unknown; retry?: () => void; inline?: boolean }>()
const info = computed(() => {
  const e = props.error
  if (e instanceof ApiError) return { message: e.message, code: e.code, requestId: e.requestId }
  if (e instanceof Error) return { message: e.message, code: '', requestId: '' }
  return { message: String(e ?? '发生错误'), code: '', requestId: '' }
})
</script>
<template>
  <div class="err" :class="{ inline }" role="alert">
    <CircleAlert class="i" aria-hidden="true" />
    <div class="body">
      <p class="msg">{{ info.message }}</p>
      <p v-if="info.code || info.requestId" class="meta mono">
        <span v-if="info.code">{{ info.code }}</span>
        <span v-if="info.requestId"> · 请求 {{ info.requestId }}</span>
      </p>
    </div>
    <button v-if="retry" class="btn btn-sm" type="button" @click="retry"><RotateCw />重试</button>
  </div>
</template>
<style scoped>
.err {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid color-mix(in srgb, var(--danger) 30%, transparent);
  background: var(--danger-soft);
  border-radius: var(--r-2);
  color: var(--ink);
}
.err.inline {
  padding: 8px 10px;
}
.i {
  width: 18px;
  height: 18px;
  color: var(--danger);
  flex: none;
  margin-top: 2px;
}
.body {
  flex: 1;
  min-width: 0;
}
.msg {
  font-weight: 500;
}
.meta {
  margin-top: 2px;
  font-size: var(--t-xs);
  color: var(--ink-3);
}
</style>
