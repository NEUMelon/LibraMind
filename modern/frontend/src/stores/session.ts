import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { auth } from '@/api/endpoints'
import { ApiError, clearCsrf, refreshCsrf } from '@/api/http'
import type { CurrentUser } from '@/api/types'

type ResetHook = () => void
const resetHooks = new Set<ResetHook>()
/** 其它 store 注册退出时的清理（个人借阅、会话等缓存）。 */
export function onSessionReset(hook: ResetHook) {
  resetHooks.add(hook)
}

export const useSession = defineStore('session', () => {
  const user = ref<CurrentUser | null>(null)
  const checked = ref(false)

  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const isStudent = computed(() => user.value?.role === 'STUDENT')
  const home = computed(() => (user.value?.role === 'ADMIN' ? '/desk' : '/shelf'))

  async function check() {
    try {
      user.value = await auth.me()
    } catch (e) {
      if (!(e instanceof ApiError) || e.status !== 401) throw e
      user.value = null
    } finally {
      checked.value = true
    }
    return user.value
  }

  async function login(username: string, password: string) {
    await refreshCsrf()
    const result = await auth.login(username, password)
    // 登录轮换 Session，旧 Token 作废，必须重新获取。
    await refreshCsrf()
    user.value = result
    checked.value = true
    return result
  }

  function reset() {
    user.value = null
    clearCsrf()
    resetHooks.forEach((h) => h())
  }

  async function logout() {
    try {
      await auth.logout()
    } catch {
      /* 会话可能已失效，仍然清理本地状态 */
    }
    reset()
  }

  return { user, checked, isAdmin, isStudent, home, check, login, logout, reset }
})
