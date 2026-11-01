import { createApp } from 'vue'
import { createPinia } from 'pinia'
import '@fontsource/noto-serif-sc/chinese-simplified-600.css'
import '@fontsource/noto-serif-sc/latin-600.css'
import './styles/tokens.css'
import './styles/base.css'
import App from './App.vue'
import { router } from './router'
import { onUnauthorized } from './api/http'
import { useSession } from './stores/session'
import { toast } from './stores/toast'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)

// 会话过期：清理本地个人数据并回到登录页。
onUnauthorized(() => {
  const session = useSession(pinia)
  if (!session.user) return
  session.reset()
  toast('info', '登录已过期，请重新登录')
  router.replace({ name: 'login', query: { next: router.currentRoute.value.fullPath } })
})

app.mount('#app')
