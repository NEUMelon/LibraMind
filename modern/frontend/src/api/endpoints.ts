// 按业务模块组织的接口封装。路径、方法与 openapi.json 一致。
import { http, Intent, type Query } from './http'
import type * as T from './types'

export const auth = {
  me: () => http.get<T.CurrentUser>('auth/me'),
  login: (username: string, password: string) =>
    http.post<T.CurrentUser>('auth/login', { username, password }, { silent401: true }),
  logout: () => http.post<boolean>('auth/logout'),
}

export const books = {
  list: (q: Query) => http.get<T.Page<T.BookView>>('books', q),
  get: (id: number) => http.get<T.BookView>(`books/${id}`),
  create: (input: T.BookInput) => http.post<T.BookView>('books', input),
  update: (id: number, input: T.BookInput) => http.patch<T.BookView>(`books/${id}`, input),
  remove: (id: number, reason: string) => http.delete<string>(`books/${id}`, { reason }),
  copies: (editionId: number, q: Query = {}) => http.get<T.Page<T.CopyView>>(`books/${editionId}/copies`, { size: 100, ...q }),
  uploadCover: (id: number, file: File) => http.upload<{ coverUrl: string }>(`books/${id}/cover`, file),
  work: (workId: number) => http.get<T.WorkView>(`works/${workId}`),
  assignWork: (editionId: number, workId: number | null, reason: string) =>
    http.patch<T.WorkView>(`editions/${editionId}/work`, { workId, reason }),
}

export const copies = {
  list: (q: Query) => http.get<T.Page<T.CopyView>>('copies', q),
  get: (id: number) => http.get<T.CopyView>(`copies/${id}`),
  byAccession: (no: string) => http.get<T.CopyView>(`copies/by-accession/${encodeURIComponent(no)}`),
  create: (input: T.CopyInput) => http.post<T.CopyView>('copies', input),
  update: (id: number, input: T.CopyInput) => http.patch<T.CopyView>(`copies/${id}`, input),
  remove: (id: number, reason: string) => http.delete<string>(`copies/${id}`, { reason }),
  labels: (copyIds: number[]) => http.post<T.Label[]>('copies/labels', { copyIds }),
  barcodeUrl: (id: number) => `/api/v1/copies/${id}/barcode`,
}

export const students = {
  list: (q: Query) => http.get<T.Page<T.StudentView>>('students', q),
  get: (id: number) => http.get<T.StudentView>(`students/${id}`),
  byNumber: (no: string) => http.get<T.StudentView>(`students/by-number/${encodeURIComponent(no)}`),
  create: (input: T.StudentInput) => http.post<T.StudentView>('students', input),
  update: (id: number, input: T.StudentInput) => http.patch<T.StudentView>(`students/${id}`, input),
  remove: (id: number, reason: string) => http.delete<string>(`students/${id}`, { reason }),
  createAccount: (id: number, username: string, password: string) =>
    http.post<{ id: number; username: string; role: string; studentId: number }>(`students/${id}/account`, { username, password }),
  loans: (id: number, q: Query = {}) => http.get<T.Page<T.LoanView>>(`students/${id}/loans`, q),
  history: (id: number, q: Query = {}) => http.get<T.Page<T.LoanView>>(`students/${id}/loan-history`, q),
  eligibility: (id: number) => http.get<T.Eligibility>('me/borrowing-eligibility', { studentId: id }),
}

export interface BorrowInput {
  studentId?: number
  accessionNos: string[]
  note?: string
}
export interface CorrectionInput {
  borrowedAt?: string | null
  dueAt?: string | null
  returnedAt?: string | null
  note: string
  reason: string
}

export const loans = {
  list: (q: Query) => http.get<T.Page<T.LoanView>>('loans', q),
  get: (id: number) => http.get<T.LoanView>(`loans/${id}`),
  preview: (input: BorrowInput) => http.post<T.BorrowPreview>('loans/preview', input),
  borrowIntent: (input: BorrowInput) => new Intent<{ loans: T.LoanView[] }>('POST', 'loans', input),
  renewIntent: (id: number) => new Intent<T.LoanView>('POST', `loans/${id}/renew`, undefined),
  correct: (id: number, input: CorrectionInput) => http.patch<T.LoanView>(`loans/${id}`, input),
  cancel: (id: number, reason: string) => http.delete<T.LoanView>(`loans/${id}`, { reason }),
}

export const returns = {
  list: (q: Query) => http.get<T.Page<T.LoanView>>('returns', q),
  returnIntent: (input: { loanId?: number; accessionNo?: string; note?: string }) =>
    new Intent<T.LoanView>('POST', 'returns', input),
  correct: (id: number, input: CorrectionInput) => http.patch<T.LoanView>(`returns/${id}`, input),
  undo: (id: number, reason: string) => http.delete<T.LoanView>(`returns/${id}`, { reason }),
}

export const me = {
  loans: (q: Query = {}) => http.get<T.Page<T.LoanView>>('me/loans', { size: 50, ...q }),
  history: (q: Query = {}) => http.get<T.Page<T.LoanView>>('me/loan-history', q),
  eligibility: () => http.get<T.Eligibility>('me/borrowing-eligibility'),
}

export const search = {
  keyword: (q: string, limit = 20, availableOnly = false) =>
    http.get<T.SearchResult>('search/books', { q, limit, availableOnly }),
  hybrid: (query: string, limit = 20, availableOnly = false) =>
    http.post<T.SearchResult>('search/hybrid', { query, limit, availableOnly }),
  semantic: (query: string, limit = 20, availableOnly = false) =>
    http.post<T.SearchResult>('search/semantic', { query, limit, availableOnly }),
}

export const vision = {
  barcode: (file: File) => http.upload<T.BarcodeResult>('vision/barcode', file),
  cover: (file: File) => http.upload<T.VisionResult>('vision/book-cover', file),
  choose: (recognitionId: number, editionId: number | null) =>
    http.post<boolean>(`vision/recognitions/${recognitionId}/choice`, { editionId }),
  copyrightPage: (file: File) => http.upload<T.VisionResult>('vision/copyright-page', file),
  confirmCopyright: (recognitionId: number, input: T.BookInput) =>
    http.post<T.BookView>(`vision/copyright-page/${recognitionId}/confirm`, input),
}

export const importing = {
  preview: (books: T.BookInput[], copiesPerEdition: number) =>
    http.post<T.ImportPreview>('import/books/preview', { books, copiesPerEdition }),
  executeIntent: (books: T.BookInput[], copiesPerEdition: number) =>
    new Intent<T.ImportResult>('POST', 'import/books/execute', { books, copiesPerEdition }),
}

export const knowledge = {
  list: (editionId?: number) => http.get<T.DocumentView[]>('knowledge', { editionId }),
  get: (id: number) => http.get<T.DocumentView>(`knowledge/${id}`),
  create: (input: T.KnowledgeInput) => http.post<T.DocumentView>('knowledge', input),
  update: (id: number, input: T.KnowledgeInput) => http.patch<T.DocumentView>(`knowledge/${id}`, input),
  remove: (id: number, reason: string) => http.delete<boolean>(`knowledge/${id}`, { reason }),
}

export const ai = {
  conversations: () => http.get<T.Conversation[]>('ai/conversations'),
  createConversation: (title: string) => http.post<T.Conversation>('ai/conversations', { title }),
  archive: (id: number) => http.delete<{ archived: boolean }>(`ai/conversations/${id}`),
  messages: (id: number, beforeId?: number) =>
    http.get<T.MessageView[]>(`ai/conversations/${id}/messages`, { beforeId }),
  send: (id: number, content: string) => http.post<T.Reply>(`ai/conversations/${id}/messages`, { content }),
  streamPath: (id: number) => `/api/v1/ai/conversations/${id}/stream`,
  ask: (question: string, editionId?: number) => http.post<T.Answer>('ai/ask', { question, editionId }),
  actions: (conversationId: number) => http.get<T.ActionView[]>('ai/actions', { conversationId }),
  action: (id: string) => http.get<T.ActionView>(`ai/actions/${id}`),
  confirm: (id: string) => http.post<T.ActionView>(`ai/actions/${id}/confirm`),
  cancel: (id: string) => http.post<T.ActionView>(`ai/actions/${id}/cancel`),
  prepareBorrow: (accessionNo: string, conversationId?: number) =>
    http.post<T.ActionView>('ai/actions/prepare-borrow', { accessionNo, conversationId }),
  prepareRenew: (loanId: number, conversationId?: number) =>
    http.post<T.ActionView>('ai/actions/prepare-renew', { loanId, conversationId }),
  prepareReturn: (loanId: number, conversationId?: number) =>
    http.post<T.ActionView>('ai/actions/prepare-return', { loanId, conversationId }),
}

export const admin = {
  overview: () => http.get<T.Overview>('dashboard/overview'),
  loanTrend: () => http.get<T.DayCount[]>('dashboard/loan-trend'),
  audit: (q: Query) => http.get<T.Page<T.AuditView>>('audit-logs', q),
  auditItem: (id: number) => http.get<T.AuditView>(`audit-logs/${id}`),
  indexStatus: () => http.get<T.IndexStatus>('admin/search/index-status'),
  aiRequests: (q: Query) => http.get<T.Page<T.Usage>>('admin/ai/requests', q),
  systemInfo: () => http.get<{ name: string; version: string; phase: string; aiEnabled: boolean }>('system/info'),
}
