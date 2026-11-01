<script setup lang="ts">
// 原生 <dialog>：自带焦点限制、Esc 关闭和顶层渲染。side=true 时作为右侧抽屉。
import { onMounted, ref, watch } from 'vue'
import { X } from 'lucide-vue-next'

const props = withDefaults(defineProps<{ open: boolean; title: string; side?: boolean; width?: string }>(), {
  side: false,
  width: '',
})
const emit = defineEmits<{ close: [] }>()
const el = ref<HTMLDialogElement>()

function sync() {
  const d = el.value
  if (!d) return
  if (props.open && !d.open) {
    d.showModal()
    // 打开时把焦点放在对话框本身，而不是右上角的关闭按钮。
    requestAnimationFrame(() => d.querySelector<HTMLElement>('.inner')?.focus())
  }
  if (!props.open && d.open) d.close()
}
watch(() => props.open, sync)
onMounted(sync)

function onBackdrop(e: MouseEvent) {
  if (e.target === el.value) emit('close')
}
</script>

<template>
  <dialog
    ref="el"
    class="dlg"
    :class="{ side }"
    :style="width ? { '--w': width } : undefined"
    :aria-label="title"
    @cancel.prevent="emit('close')"
    @mousedown="onBackdrop"
  >
    <div v-if="open" class="inner" tabindex="-1">
      <header class="head">
        <h2 class="title">{{ title }}</h2>
        <slot name="head-extra" />
        <button class="btn btn-quiet btn-icon" type="button" aria-label="关闭" @click="emit('close')"><X /></button>
      </header>
      <div class="body"><slot /></div>
      <footer v-if="$slots.foot" class="foot"><slot name="foot" /></footer>
    </div>
  </dialog>
</template>

<style scoped>
.dlg {
  --w: 560px;
  width: min(var(--w), calc(100vw - 32px));
  max-height: min(86vh, 900px);
  padding: 0;
  border: 1px solid var(--rule);
  border-radius: var(--r-4);
  background: var(--surface);
  color: var(--ink);
  box-shadow: var(--shadow-3);
}
.dlg::backdrop {
  background: rgba(24, 20, 14, 0.38);
}
.dlg[open] {
  animation: pop 0.16s var(--ease);
}
.dlg.side {
  --w: 620px;
  margin: 0 0 0 auto;
  height: 100vh;
  max-height: 100vh;
  border-radius: 0;
  border-width: 0 0 0 1px;
}
.dlg.side[open] {
  animation: slide 0.2s var(--ease);
}
.inner {
  outline: none;
  display: flex;
  flex-direction: column;
  max-height: inherit;
  height: 100%;
}
.head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 14px 12px 20px;
  border-bottom: 1px solid var(--rule);
}
.title {
  flex: 1;
  font-size: var(--t-lg);
}
.body {
  padding: 18px 20px 20px;
  overflow: auto;
  flex: 1;
}
.foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  border-top: 1px solid var(--rule);
  background: var(--surface-sunk);
}
@keyframes pop {
  from { opacity: 0; transform: translateY(6px) scale(0.99); }
}
@keyframes slide {
  from { transform: translateX(24px); opacity: 0.4; }
}
@media (max-width: 760px) {
  .dlg.side { --w: 100vw; width: 100vw; }
  .dlg:not(.side) { margin: auto auto 0; width: 100vw; max-width: 100vw; border-radius: var(--r-4) var(--r-4) 0 0; }
}
</style>
