<script setup lang="ts">
// 表单级错误：字段错误显示在字段下，其余错误（如学号重复）显示在这里。
import { computed } from 'vue'
import { ApiError } from '@/api/http'
const props = defineProps<{ error: unknown }>()
const text = computed(() => {
  const e = props.error
  if (!e) return ''
  if (e instanceof ApiError) return e.details ? '请修正标出的字段' : e.message
  return e instanceof Error ? e.message : String(e)
})
</script>
<template>
  <p v-if="text" class="fe" role="alert">{{ text }}</p>
</template>
<style scoped>
.fe {
  padding: 8px 10px;
  border-radius: var(--r-2);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: var(--t-sm);
}
</style>
