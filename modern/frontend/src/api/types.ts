// 与后端 openapi.json 对齐的类型。字段名保持后端原样。

export type Role = 'ADMIN' | 'STUDENT'

export interface CurrentUser {
  id: number
  username: string
  role: Role
  studentId: number | null
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface BookView {
  id: number
  workId: number | null
  title: string
  subtitle: string
  authors: string
  translators: string
  isbn13: string
  publisher: string
  publicationDate: string
  editionStatement: string
  volume: string
  language: string
  classificationNo: string
  category: string
  description: string
  coverUrl: string
  sourceType: string
  sourceRecordId: string
  sourceUrl: string
  copyCount: number
  availableCount: number
  archived: boolean
}

export type BookInput = Pick<
  BookView,
  | 'title'
  | 'subtitle'
  | 'authors'
  | 'translators'
  | 'isbn13'
  | 'publisher'
  | 'publicationDate'
  | 'editionStatement'
  | 'volume'
  | 'language'
  | 'classificationNo'
  | 'category'
  | 'description'
  | 'sourceUrl'
> & { workId?: number | null }

export type CirculationStatus = 'AVAILABLE' | 'BORROWED' | 'MAINTENANCE' | 'OFF_SHELF'

export interface CopyView {
  id: number
  accessionNo: string
  editionId: number
  title: string
  authors: string
  editionStatement: string
  isbn13: string
  callNumber: string
  location: string
  circulationStatus: CirculationStatus
  archived: boolean
}

export interface CopyInput {
  accessionNo: string
  editionId: number
  callNumber: string
  location: string
  circulationStatus: CirculationStatus
}

export interface WorkView {
  id: number
  displayTitle: string
  displayCreators: string
  editions: BookView[]
  copyCount: number
  availableCount: number
}

export interface StudentView {
  id: number
  studentNo: string
  name: string
  college: string
  major: string
  enabled: boolean
  currentBorrowedCount: number
}

export interface StudentInput {
  studentNo: string
  name: string
  college: string
  major: string
  enabled: boolean
}

export type LoanStatus = 'ACTIVE' | 'RETURNED' | 'CANCELLED'

export interface LoanView {
  id: number
  studentId: number
  studentNo: string
  studentName: string
  copyId: number
  accessionNo: string
  editionId: number
  title: string
  borrowedAt: string
  dueAt: string
  returnedAt: string | null
  status: LoanStatus
  renewCount: number
  overdue: boolean
  note: string
  cancellationReason: string
}

export interface Eligibility {
  studentId: number
  enabled: boolean
  currentBorrowedCount: number
  limit: number
  remaining: number
  overdueCount: number
  canBorrow: boolean
}

export interface CopyCheck {
  accessionNo: string
  copy: CopyView | null
  available: boolean
  reason: string
}

export interface BorrowPreview {
  eligibility: Eligibility
  copies: CopyCheck[]
  valid: boolean
}

export interface SearchHit {
  book: BookView
  score: number
  matchReasons: string[]
}

export interface SearchResult {
  query: string
  mode: string
  hits: SearchHit[]
  degraded: string[]
  latencyMs: number
}

export interface Overview {
  students: number
  editions: number
  copies: number
  available: number
  activeLoans: number
  overdueLoans: number
}

export interface DayCount {
  date: string
  count: number
}

export interface AuditView {
  id: number
  operatorId: number
  operationType: string
  targetType: string
  targetId: number
  beforeSnapshot: string
  afterSnapshot: string
  reason: string
  requestId: string
  createdAt: string
}

export interface DocumentView {
  id: number
  editionId: number | null
  title: string
  content: string
  documentType: string
  source: string
  sourceUrl: string
  visibility: string
  version: number
  enabled: boolean
}

export interface KnowledgeInput {
  editionId: number | null
  title: string
  content: string
  documentType: string
  source: string
  sourceUrl: string
  visibility: string
}

export interface IndexStatus {
  ready: boolean
  loaded: number
  pending: number
  failed: number
  stale: number
  modelKey: string
  workerEnabled: boolean
}

export interface Usage {
  id: number
  type: string
  model: string
  status: string
  inputTokens: number
  outputTokens: number
  estimatedUsd: number
  latencyMs: number
  errorCode: string
  createdAt: string
}

export interface ExtractedBook {
  title: string
  subtitle: string
  authors: string
  translators: string
  isbn13: string
  publisher: string
  publicationDate: string
  editionStatement: string
  volume: string
  language: string
  classificationNo: string
  confidenceNotes: string[]
}

export interface VisionResult {
  recognitionId: number
  kind: string
  outcome: string
  extracted: ExtractedBook | null
  candidates: WorkView[]
  warnings: string[]
  model: string
}

export interface BarcodeResult {
  recognitionId: number
  format: string
  value: string
  found: boolean
  book: BookView | null
  copy: CopyView | null
}

export interface ImportError {
  row: number
  message: string
}
export interface ImportPreview {
  total: number
  newEditions: number
  existingEditions: number
  errors: ImportError[]
}
export interface ImportResult {
  created: number
  updated: number
  copiesCreated: number
}

export interface Label {
  copy: CopyView
  barcodeUrl: string
}

// ---------- AI ----------
export interface Conversation {
  id: number
  title: string
  updatedAt: string
}

export interface Citation {
  id: string
  chunkId: number
  documentId: number
  editionId: number | null
  title: string
  excerpt: string
  source: string
  sourceUrl: string
  version: number
}

export interface ToolTrace {
  name: string
  status: string
  code: string
  latencyMs: number
}

export interface ActionSnapshot {
  studentId: number
  copyId: number
  accessionNo: string
  loanId: number | null
  loanVersion: number | null
  title: string
  expectedDueAt: string | null
}

export type ActionStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'EXPIRED'
export type ActionType = 'BORROW' | 'RENEW' | 'RETURN'

export interface ActionView {
  id: string
  type: ActionType
  status: ActionStatus
  expiresAt: string
  preview: ActionSnapshot
  result: unknown
  failureCode: string | null
}

export interface Reply {
  content: string
  editionReferences: number[]
  citations: Citation[]
  actions: ActionView[]
  tools: ToolTrace[]
  mode: string
  latencyMs: number
}

export interface MessageView {
  id: number
  role: string
  content: string
  model: string
  editionReferences: number[]
  citations: Citation[]
  createdAt: string
}

export interface Answer {
  answer: string
  citations: Citation[]
  degraded: string[]
  supported: boolean
}
