<script setup lang="ts">
import { CircleCheck, CircleAlert, Info, X } from 'lucide-vue-next'
import { toasts, dismiss } from '@/stores/toast'
const icons = { ok: CircleCheck, danger: CircleAlert, info: Info }
</script>
<template>
  <div class="host" aria-live="polite">
    <TransitionGroup name="t">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="t.tone" role="status">
        <component :is="icons[t.tone]" class="i" aria-hidden="true" />
        <div class="b">
          <p class="title">{{ t.title }}</p>
          <p v-if="t.detail" class="detail">{{ t.detail }}</p>
        </div>
        <button class="x" type="button" aria-label="关闭提示" @click="dismiss(t.id)"><X /></button>
      </div>
    </TransitionGroup>
  </div>
</template>
<style scoped>
.host {
  position: fixed;
  right: 20px;
  bottom: 20px;
  z-index: 100;
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: min(380px, calc(100vw - 32px));
}
.toast {
  display: flex;
  gap: 10px;
  padding: 12px 12px 12px 14px;
  background: var(--ink);
  color: var(--paper);
  border-radius: var(--r-3);
  box-shadow: var(--shadow-2);
}
.i {
  width: 18px;
  height: 18px;
  flex: none;
  margin-top: 1px;
}
.ok .i { color: #9fd3b9; }
.danger .i { color: #f2a094; }
.info .i { color: #a9c6e6; }
.b { flex: 1; min-width: 0; }
.title { font-weight: 500; }
.detail { font-size: var(--t-sm); opacity: 0.75; margin-top: 2px; }
.x {
  border: 0;
  background: none;
  color: inherit;
  opacity: 0.6;
  cursor: pointer;
  padding: 0;
  width: 20px;
  height: 20px;
}
.x svg { width: 16px; height: 16px; }
.t-enter-active, .t-leave-active { transition: all 0.2s var(--ease); }
.t-enter-from, .t-leave-to { opacity: 0; transform: translateY(8px); }
@media (max-width: 760px) {
  .host { right: 16px; left: 16px; bottom: calc(76px + env(safe-area-inset-bottom)); width: auto; }
}
</style>
