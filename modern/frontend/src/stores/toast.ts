import { reactive } from 'vue'

export interface Toast {
  id: number
  tone: 'ok' | 'danger' | 'info'
  title: string
  detail?: string
}

let seq = 0
export const toasts = reactive<Toast[]>([])

export function toast(tone: Toast['tone'], title: string, detail?: string, ms = tone === 'danger' ? 7000 : 3600) {
  const id = ++seq
  toasts.push({ id, tone, title, detail })
  window.setTimeout(() => dismiss(id), ms)
  return id
}
export function dismiss(id: number) {
  const i = toasts.findIndex((t) => t.id === id)
  if (i >= 0) toasts.splice(i, 1)
}
