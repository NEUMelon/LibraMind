<script setup lang="ts">
withDefaults(defineProps<{ rows?: number; cols?: number }>(), { rows: 6, cols: 5 })
</script>
<template>
  <div class="sk" aria-busy="true" aria-label="正在加载">
    <div v-for="r in rows" :key="r" class="row">
      <span v-for="c in cols" :key="c" class="cell" :style="{ flex: c === 2 ? 3 : 1, opacity: 1 - r * 0.08 }"></span>
    </div>
  </div>
</template>
<style scoped>
.sk {
  padding: 6px 12px;
}
.row {
  display: flex;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid var(--rule);
}
.row:last-child {
  border-bottom: 0;
}
.cell {
  height: 12px;
  border-radius: 4px;
  background: linear-gradient(90deg, var(--paper-2), var(--surface-sunk), var(--paper-2));
  background-size: 200% 100%;
  animation: shimmer 1.4s infinite linear;
}
@keyframes shimmer {
  from { background-position: 200% 0; }
  to { background-position: -200% 0; }
}
</style>
