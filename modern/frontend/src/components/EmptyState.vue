<script setup lang="ts">
import type { Component } from 'vue'
defineProps<{ icon?: Component; title: string; text?: string; compact?: boolean }>()
</script>
<template>
  <div class="empty" :class="{ compact }">
    <component :is="icon" v-if="icon" class="icon" aria-hidden="true" />
    <p class="title">{{ title }}</p>
    <p v-if="text" class="text">{{ text }}</p>
    <div v-if="$slots.default" class="actions"><slot /></div>
  </div>
</template>
<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 56px 24px;
  color: var(--ink-3);
}
.empty.compact {
  padding: 28px 16px;
}
.icon {
  width: 28px;
  height: 28px;
  margin-bottom: 12px;
  color: var(--ink-4);
  stroke-width: 1.5;
}
.title {
  color: var(--ink-2);
  font-weight: 500;
}
.text {
  margin-top: 4px;
  font-size: var(--t-sm);
  max-width: 36ch;
}
.actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
}
</style>
