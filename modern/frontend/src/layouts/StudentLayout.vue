<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BookMarked, Search, ScanBarcode, MessagesSquare, LogOut } from 'lucide-vue-next'
import { useSession } from '@/stores/session'
import ToastHost from '@/components/ToastHost.vue'

const session = useSession()
const route = useRoute()
const router = useRouter()
const tabs = [
  { to: '/shelf', label: '书架', icon: BookMarked, match: ['shelf', 'history'] },
  { to: '/search', label: '找书', icon: Search, match: ['search', 'book'] },
  { to: '/scan', label: '识书', icon: ScanBarcode, match: ['scan'] },
  { to: '/assistant', label: '助手', icon: MessagesSquare, match: ['assistant'] },
]
const section = computed(() => route.path.split('/')[1])
async function logout() {
  await session.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="s-shell">
    <header class="top">
      <RouterLink to="/shelf" class="brand" aria-label="LibraMind 首页">
        <svg viewBox="0 0 32 32" aria-hidden="true"><rect width="32" height="32" rx="7" fill="var(--accent)"/><path d="M9 8h4v16H9zM14.5 8h3.5v16h-3.5zM19.5 9.2l3.4-.9 3.6 14.6-3.4.9z" fill="var(--accent-ink)"/></svg>
        <span>LibraMind</span>
      </RouterLink>
      <nav class="top-tabs" aria-label="主导航">
        <RouterLink v-for="t in tabs" :key="t.to" :to="t.to" class="tt" :class="{ on: t.match.includes(section) }">
          <component :is="t.icon" aria-hidden="true" />{{ t.label }}
        </RouterLink>
      </nav>
      <button class="btn btn-quiet btn-sm logout" type="button" @click="logout"><LogOut />退出</button>
    </header>
    <main class="content">
      <RouterView />
    </main>
    <nav class="bottom" aria-label="主导航">
      <RouterLink
        v-for="t in tabs"
        :key="t.to"
        :to="t.to"
        class="bt"
        :class="{ on: t.match.includes(section) }"
        :aria-current="t.match.includes(section) ? 'page' : undefined"
      >
        <component :is="t.icon" aria-hidden="true" />
        <span>{{ t.label }}</span>
      </RouterLink>
    </nav>
    <ToastHost />
  </div>
</template>

<style scoped>
.s-shell {
  min-height: 100vh;
  min-height: 100dvh;
}
.top {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 20px;
  height: 56px;
  padding: 0 max(20px, calc((100vw - 960px) / 2));
  background: color-mix(in srgb, var(--paper) 90%, transparent);
  backdrop-filter: saturate(1.2) blur(10px);
  border-bottom: 1px solid var(--rule);
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ink);
  font-weight: 650;
  text-decoration: none !important;
}
.brand svg {
  width: 26px;
  height: 26px;
}
.top-tabs {
  display: flex;
  gap: 2px;
  margin-left: 12px;
}
.tt {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 12px;
  border-radius: var(--r-2);
  color: var(--ink-2);
  text-decoration: none !important;
}
.tt svg {
  width: 16px;
  height: 16px;
}
.tt:hover {
  background: var(--paper-2);
}
.tt.on {
  color: var(--accent-text);
  background: var(--accent-soft);
  font-weight: 600;
}
.logout {
  margin-left: auto;
}
.content {
  max-width: 960px;
  margin: 0 auto;
  padding: 0 20px 48px;
}
.bottom {
  display: none;
}
@media (max-width: 760px) {
  .top {
    height: 50px;
    padding: 0 16px;
  }
  .top-tabs {
    display: none;
  }
  .content {
    padding: 0 16px calc(88px + env(safe-area-inset-bottom));
  }
  .bottom {
    position: fixed;
    z-index: 30;
    left: 0;
    right: 0;
    bottom: 0;
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    padding: 6px 8px calc(6px + env(safe-area-inset-bottom));
    background: color-mix(in srgb, var(--surface) 94%, transparent);
    backdrop-filter: blur(12px);
    border-top: 1px solid var(--rule);
  }
  .bt {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    padding: 6px 0 4px;
    color: var(--ink-3);
    font-size: 11px;
    text-decoration: none !important;
    border-radius: var(--r-2);
  }
  .bt svg {
    width: 22px;
    height: 22px;
    stroke-width: 1.7;
  }
  .bt.on {
    color: var(--accent-text);
    font-weight: 600;
  }
  .bt.on svg {
    stroke-width: 2.1;
  }
}
</style>
