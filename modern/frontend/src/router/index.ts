import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useSession } from '@/stores/session'

const admin = (path: string, name: string, component: RouteRecordRaw['component'], title: string): RouteRecordRaw => ({
  path, name, component, meta: { role: 'ADMIN', title },
} as RouteRecordRaw)
const student = (path: string, name: string, component: RouteRecordRaw['component'], title: string): RouteRecordRaw => ({
  path, name, component, meta: { role: 'STUDENT', title },
} as RouteRecordRaw)

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    children: [
      admin('desk', 'desk', () => import('@/views/admin/CirculationDesk.vue'), '流通台'),
      admin('students', 'students', () => import('@/views/admin/StudentsView.vue'), '学生管理'),
      admin('books', 'books', () => import('@/views/admin/BooksView.vue'), '图书与副本'),
      admin('loans', 'loans', () => import('@/views/admin/LoansView.vue'), '借书记录'),
      admin('returns', 'returns', () => import('@/views/admin/ReturnsView.vue'), '还书记录'),
      admin('identify', 'identify', () => import('@/views/admin/IdentifyView.vue'), '识书'),
      admin('import', 'import', () => import('@/views/admin/ImportView.vue'), '批量导入'),
      admin('labels', 'labels', () => import('@/views/admin/LabelsView.vue'), '条码标签'),
      admin('cataloging', 'cataloging', () => import('@/views/admin/CatalogingView.vue'), '版权页编目'),
      admin('knowledge', 'knowledge', () => import('@/views/admin/KnowledgeView.vue'), '知识资料'),
      admin('stats', 'stats', () => import('@/views/admin/StatsView.vue'), '统计'),
      admin('ai-ops', 'ai-ops', () => import('@/views/admin/AiOpsView.vue'), 'AI 与索引'),
      admin('audit', 'audit', () => import('@/views/admin/AuditView.vue'), '审计日志'),
    ],
  },
  {
    path: '/',
    component: () => import('@/layouts/StudentLayout.vue'),
    children: [
      student('shelf', 'shelf', () => import('@/views/student/ShelfView.vue'), '我的书架'),
      student('search', 'search', () => import('@/views/student/SearchView.vue'), '馆藏检索'),
      student('book/:id', 'book', () => import('@/views/student/BookDetailView.vue'), '图书详情'),
      student('history', 'history', () => import('@/views/student/HistoryView.vue'), '借阅历史'),
      student('scan', 'scan', () => import('@/views/student/ScanView.vue'), '识书'),
      student('assistant/:id?', 'assistant', () => import('@/views/student/AssistantView.vue'), 'AI 助手'),
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: (_to, _from, saved) => saved ?? { top: 0 },
})

router.beforeEach(async (to) => {
  const session = useSession()
  if (!session.checked) {
    try {
      await session.check()
    } catch {
      /* 后端不可达：交给登录页显示错误 */
    }
  }
  if (to.meta.public) {
    if (session.user && to.name === 'login') return session.home
    return true
  }
  if (!session.user) return { name: 'login', query: to.fullPath !== '/' ? { next: to.fullPath } : {} }
  if (to.path === '/') return session.home
  const role = to.meta.role as string | undefined
  if (role && role !== session.user.role) return session.home
  return true
})

router.afterEach((to) => {
  const t = to.meta.title as string | undefined
  document.title = t ? `${t} · LibraMind` : 'LibraMind'
})
