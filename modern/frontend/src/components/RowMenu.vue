<script setup lang="ts">
// 表格行的“更多操作”菜单：方向键移动，Esc 关闭，点击外部关闭。
import { nextTick, onBeforeUnmount, ref, type Component } from 'vue'
import { Ellipsis } from 'lucide-vue-next'

export interface MenuItem {
  label: string
  icon?: Component
  danger?: boolean
  disabled?: boolean
  run: () => void
}
const props = defineProps<{ items: MenuItem[]; label?: string }>()
const open = ref(false)
const root = ref<HTMLElement>()
const menu = ref<HTMLElement>()
const up = ref(false)

function toggle() {
  open.value ? close() : show()
}
async function show() {
  open.value = true
  const rect = root.value!.getBoundingClientRect()
  up.value = window.innerHeight - rect.bottom < 40 + props.items.length * 36
  await nextTick()
  menu.value?.querySelector<HTMLElement>('button:not(:disabled)')?.focus()
  document.addEventListener('mousedown', outside)
}
function close(focus = false) {
  open.value = false
  document.removeEventListener('mousedown', outside)
  if (focus) root.value?.querySelector<HTMLElement>('.trigger')?.focus()
}
function outside(e: MouseEvent) {
  if (!root.value?.contains(e.target as Node)) close()
}
function onKey(e: KeyboardEvent) {
  const buttons = [...(menu.value?.querySelectorAll<HTMLElement>('button:not(:disabled)') ?? [])]
  const i = buttons.indexOf(document.activeElement as HTMLElement)
  if (e.key === 'Escape') {
    e.preventDefault()
    e.stopPropagation()
    close(true)
  } else if (e.key === 'ArrowDown') {
    e.preventDefault()
    buttons[(i + 1) % buttons.length]?.focus()
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    buttons[(i - 1 + buttons.length) % buttons.length]?.focus()
  }
}
function pick(item: MenuItem) {
  close()
  item.run()
}
onBeforeUnmount(() => document.removeEventListener('mousedown', outside))
</script>

<template>
  <div ref="root" class="rm" @click.stop @keydown.enter.stop>
    <button class="btn btn-sm btn-quiet btn-icon trigger" type="button" :aria-label="label ?? '更多操作'" aria-haspopup="menu" :aria-expanded="open" @click="toggle">
      <Ellipsis />
    </button>
    <div v-if="open" ref="menu" class="menu" :class="{ up }" role="menu" @keydown="onKey">
      <button
        v-for="item in items"
        :key="item.label"
        type="button"
        role="menuitem"
        :disabled="item.disabled"
        :class="{ danger: item.danger }"
        @click="pick(item)"
      >
        <component :is="item.icon" v-if="item.icon" aria-hidden="true" />{{ item.label }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.rm {
  position: relative;
  display: inline-block;
}
.menu {
  position: absolute;
  right: 0;
  top: calc(100% + 4px);
  z-index: 20;
  min-width: 150px;
  padding: 4px;
  border: 1px solid var(--rule-strong);
  border-radius: var(--r-3);
  background: var(--surface);
  box-shadow: var(--shadow-2);
  text-align: left;
}
.menu.up {
  top: auto;
  bottom: calc(100% + 4px);
}
.menu button {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 32px;
  padding: 0 10px;
  border: 0;
  border-radius: var(--r-2);
  background: none;
  color: var(--ink);
  font-size: var(--t-md);
  white-space: nowrap;
  cursor: pointer;
}
.menu button:hover,
.menu button:focus-visible {
  background: var(--paper-2);
  box-shadow: none;
}
.menu button:disabled {
  color: var(--ink-4);
  cursor: not-allowed;
}
.menu button.danger {
  color: var(--danger);
}
.menu svg {
  width: 15px;
  height: 15px;
  color: var(--ink-3);
}
.menu button.danger svg {
  color: var(--danger);
}
</style>
