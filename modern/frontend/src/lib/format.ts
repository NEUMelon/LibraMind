// 显示格式：时间统一按浏览器本地时区显示；状态枚举在这里映射中文。
import type { ActionStatus, ActionType, CirculationStatus, LoanStatus } from '@/api/types'

const dateFmt = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
const dateTimeFmt = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
})
const shortFmt = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric' })
const timeFmt = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })

export function fmtDate(iso?: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : dateFmt.format(d).replaceAll('/', '-')
}
export function fmtDateTime(iso?: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : dateTimeFmt.format(d).replaceAll('/', '-')
}
export function fmtShort(iso?: string | null) {
  if (!iso) return '—'
  return shortFmt.format(new Date(iso))
}
export function fmtTime(iso?: string | null) {
  if (!iso) return ''
  return timeFmt.format(new Date(iso))
}

const DAY = 86_400_000
function startOfDay(d: Date) {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}
/** 以本地日历日计算距应还日的天数；负数为逾期天数。 */
export function daysUntil(iso: string, now = new Date()) {
  return Math.round((startOfDay(new Date(iso)) - startOfDay(now)) / DAY)
}

export interface DueInfo {
  label: string
  tone: 'ok' | 'warn' | 'danger'
}
/** 逾期以服务端 overdue 字段为准，天数只用于文案。 */
export function dueInfo(dueAt: string, overdue: boolean): DueInfo {
  const days = daysUntil(dueAt)
  if (overdue) return { label: days < 0 ? `已逾期 ${-days} 天` : '已逾期', tone: 'danger' }
  if (days <= 0) return { label: '今天到期', tone: 'warn' }
  if (days <= 5) return { label: `${days} 天后到期`, tone: 'warn' }
  return { label: `还剩 ${days} 天`, tone: 'ok' }
}

export const copyStatus: Record<CirculationStatus, { label: string; tone: string }> = {
  AVAILABLE: { label: '在架可借', tone: 'ok' },
  BORROWED: { label: '已借出', tone: 'warn' },
  MAINTENANCE: { label: '维护中', tone: 'info' },
  OFF_SHELF: { label: '已下架', tone: '' },
}

export const loanStatus: Record<LoanStatus, { label: string; tone: string }> = {
  ACTIVE: { label: '在借', tone: 'warn' },
  RETURNED: { label: '已归还', tone: 'ok' },
  CANCELLED: { label: '已作废', tone: '' },
}

export const actionStatus: Record<ActionStatus, { label: string; tone: string }> = {
  PENDING: { label: '待确认', tone: 'info' },
  SUCCEEDED: { label: '已完成', tone: 'ok' },
  FAILED: { label: '未执行', tone: 'danger' },
  CANCELLED: { label: '已取消', tone: '' },
  EXPIRED: { label: '已过期', tone: '' },
}

export const actionType: Record<ActionType, { verb: string; title: string }> = {
  BORROW: { verb: '确认借阅', title: '借阅' },
  RENEW: { verb: '确认续借', title: '续借' },
  RETURN: { verb: '确认归还', title: '归还' },
}

export const errorHints: Record<string, string> = {
  COPY_UNAVAILABLE: '这一册当前不可借',
  BORROW_LIMIT_EXCEEDED: '已达到同时在借上限',
  LOAN_NOT_ACTIVE: '这条借阅已不是在借状态',
  LOAN_OVERDUE: '已逾期的借阅不能续借，请先归还',
  RENEW_LIMIT_REACHED: '已达到续借次数上限',
  RENEW_NOT_ELIGIBLE: '已逾期或已达到续借上限，不能续借',
  BORROW_NOT_ELIGIBLE: '当前不能借这一册（额度已满、账号停用或这册已不可借）',
  ACTION_STALE: '情况已变化，请重新准备',
  IDEMPOTENCY_KEY_REUSED: '同一操作的内容被修改，请重新提交',
  CONVERSATION_BUSY: '上一条回答还在生成',
  AI_UNAVAILABLE: 'AI 服务当前不可用',
  AI_BUDGET_EXCEEDED: '今日 AI 预算已用完',
  AI_REQUEST_LIMIT: '本轮请求超出限制',
  AI_BUSY: 'AI 当前繁忙，请稍后',
  REPLAY_MISS: '回放模式下没有匹配的录制',
  STUDENT_DISABLED: '该学生已停用',
  ACCESS_DENIED: '没有权限',
  NOT_FOUND: '不存在或无权查看',
}

export function degradedText(list: string[]) {
  if (!list?.length) return ''
  const map: Record<string, string> = { vector: '语义向量', keyword: '关键词', model: '模型' }
  return list.map((d) => map[d] ?? d).join('、')
}

export function plural(n: number, unit: string) {
  return `${n.toLocaleString('zh-CN')} ${unit}`
}

/** 书目日期来自出版方目录，格式不一（2018-09-29、5/10/2006…），只提取年份用于列表。 */
export function pubYear(date?: string | null) {
  return date?.match(/(1[89]|20)\d{2}/)?.[0] ?? ''
}
