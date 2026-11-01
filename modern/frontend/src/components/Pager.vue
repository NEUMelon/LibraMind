<script setup lang="ts">
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
const props = defineProps<{ page: number; totalPages: number; total: number; size: number }>()
const emit = defineEmits<{ go: [page: number] }>()
function go(p: number) {
  if (p >= 1 && p <= Math.max(1, props.totalPages) && p !== props.page) emit('go', p)
}
</script>
<template>
  <nav class="pager" aria-label="分页">
    <span class="muted num">
      共 {{ total.toLocaleString('zh-CN') }} 条
      <template v-if="total > 0"> · 第 {{ (page - 1) * size + 1 }}–{{ Math.min(page * size, total) }} 条</template>
    </span>
    <div class="ctrl">
      <button class="btn btn-sm btn-icon" type="button" :disabled="page <= 1" aria-label="上一页" @click="go(page - 1)">
        <ChevronLeft />
      </button>
      <span class="num cur">{{ page }} / {{ Math.max(1, totalPages) }}</span>
      <button class="btn btn-sm btn-icon" type="button" :disabled="page >= totalPages" aria-label="下一页" @click="go(page + 1)">
        <ChevronRight />
      </button>
    </div>
  </nav>
</template>
<style scoped>
.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 2px 0;
  font-size: var(--t-sm);
}
.ctrl {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cur {
  min-width: 56px;
  text-align: center;
  color: var(--ink-2);
}
</style>
