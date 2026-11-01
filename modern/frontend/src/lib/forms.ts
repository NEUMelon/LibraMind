import { ApiError } from '@/api/http'

/** 后端校验失败时 details 是字段名到原因的映射。 */
export function fieldError(error: unknown, field: string): string {
  if (error instanceof ApiError && error.details) return error.details[field] ?? ''
  return ''
}

/** datetime-local 值（本地时间）与 ISO-8601（UTC）互转。 */
export function toLocalInput(iso?: string | null) {
  if (!iso) return ''
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
export function fromLocalInput(v: string) {
  return v ? new Date(v).toISOString() : null
}
