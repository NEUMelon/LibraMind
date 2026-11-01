<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ScanLine, Users, Library, ArrowUpRight, ArrowDownLeft, FileUp, Barcode, ScanText,
  BookOpenText, ShieldCheck, ChartLine, LogOut, Menu, Cpu, Camera,
} from 'lucide-vue-next'
import { useSession } from '@/stores/session'
import ToastHost from '@/components/ToastHost.vue'

const session = useSession()
const router = useRouter()
const route = useRoute()
const open = ref(false)
watch(() => route.fullPath, () => (open.value = false))

const groups = [
  { title: '流通', items: [
    { to: '/desk', label: '流通台', icon: ScanLine, key: 'desk' },
    { to: '/loans', label: '借书记录', icon: ArrowUpRight, key: 'loans' },
    { to: '/returns', label: '还书记录', icon: ArrowDownLeft, key: 'returns' },
    { to: '/identify', label: '识书', icon: Camera, key: 'identify' },
  ] },
  { title: '读者与馆藏', items: [
    { to: '/students', label: '学生管理', icon: Users, key: 'students' },
    { to: '/books', label: '图书与副本', icon: Library, key: 'books' },
    { to: '/import', label: '批量导入', icon: FileUp, key: 'import' },
    { to: '/labels', label: '条码标签', icon: Barcode, key: 'labels' },
    { to: '/cataloging', label: '版权页编目', icon: ScanText, key: 'cataloging' },
  ] },
  { title: '知识与系统', items: [
    { to: '/knowledge', label: '知识资料', icon: BookOpenText, key: 'knowledge' },
    { to: '/stats', label: '统计', icon: ChartLine, key: 'stats' },
    { to: '/ai-ops', label: 'AI 与索引', icon: Cpu, key: 'ai-ops' },
    { to: '/audit', label: '审计日志', icon: ShieldCheck, key: 'audit' },
  ] },
]
const current = computed(() => route.path.split('/')[1])

async function logout() {
  await session.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="shell">
    <aside class="side" :class="{ open }">
      <div class="brand">
        <svg viewBox="0 0 32 32" aria-hidden="true"><rect width="32" height="32" rx="7" fill="var(--accent)"/><path d="M9 8h4v16H9zM14.5 8h3.5v16h-3.5zM19.5 9.2l3.4-.9 3.6 14.6-3.4.9z" fill="var(--accent-ink)"/></svg>
        <div>
          <p class="name">LibraMind</p>
          <p class="role">馆员工作台</p>
        </div>
      </div>
      <nav aria-label="管理端导航">
        <div v-for="g in groups" :key="g.title" class="group">
          <p class="gt">{{ g.title }}</p>
          <RouterLink
            v-for="item in g.items"
            :key="item.key"
            :to="item.to"
            class="nav"
            :class="{ active: current === item.key }"
            :aria-current="current === item.key ? 'page' : undefined"
          >
            <component :is="item.icon" aria-hidden="true" />{{ item.label }}
          </RouterLink>
        </div>
      </nav>
      <div class="me">
        <div class="who">
          <span class="avatar">{{ session.user?.username.slice(0, 1).toUpperCase() }}</span>
          <div class="truncate">
            <p class="u truncate">{{ session.user?.username }}</p>
            <p class="r">管理员</p>
          </div>
        </div>
        <button class="btn btn-quiet btn-icon" type="button" aria-label="退出登录" title="退出登录" @click="logout"><LogOut /></button>
      </div>
    </aside>
    <div v-if="open" class="scrim" @click="open = false"></div>
    <main class="main">
      <div class="mobile-bar">
        <button class="btn btn-quiet btn-icon" type="button" aria-label="打开导航" @click="open = true"><Menu /></button>
        <span class="mb-title">{{ route.meta.title }}</span>
      </div>
      <RouterView v-slot="{ Component }">
        <component :is="Component" />
      </RouterView>
    </main>
    <ToastHost />
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: 224px 1fr;
  min-height: 100vh;
}
.side {
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--rule);
  background: var(--paper-2);
  padding: 16px 10px 10px;
  overflow-y: auto;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 8px 18px;
}
.brand svg {
  width: 30px;
  height: 30px;
  flex: none;
}
.name {
  font-weight: 650;
  font-size: 15px;
  letter-spacing: 0.01em;
}
.role {
  font-size: var(--t-xs);
  color: var(--ink-3);
}
.group + .group {
  margin-top: 14px;
}
.gt {
  padding: 0 10px 4px;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-4);
  letter-spacing: 0.08em;
}
.nav {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 34px;
  padding: 0 10px;
  border-radius: var(--r-2);
  color: var(--ink-2);
  font-size: var(--t-md);
  text-decoration: none;
}
.nav svg {
  width: 17px;
  height: 17px;
  stroke-width: 1.8;
  color: var(--ink-3);
}
.nav:hover {
  background: color-mix(in srgb, var(--ink) 5%, transparent);
  color: var(--ink);
  text-decoration: none;
}
.nav.active {
  background: var(--surface);
  color: var(--ink);
  font-weight: 600;
  box-shadow: var(--shadow-1);
}
.nav.active svg {
  color: var(--accent);
}
.me {
  margin-top: auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 10px 4px 2px 8px;
  border-top: 1px solid var(--rule);
}
.who {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.avatar {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex: none;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent-text);
  font-weight: 600;
  font-size: var(--t-sm);
}
.u {
  font-size: var(--t-sm);
  font-weight: 500;
}
.r {
  font-size: 11px;
  color: var(--ink-3);
}
.main {
  min-width: 0;
}
.mobile-bar,
.scrim {
  display: none;
}
@media (max-width: 960px) {
  .shell {
    grid-template-columns: 1fr;
  }
  .side {
    position: fixed;
    z-index: 50;
    left: 0;
    width: 260px;
    transform: translateX(-100%);
    transition: transform 0.2s var(--ease);
    box-shadow: var(--shadow-3);
  }
  .side.open {
    transform: none;
  }
  .scrim {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 40;
    background: rgba(24, 20, 14, 0.3);
  }
  .mobile-bar {
    display: flex;
    align-items: center;
    gap: 6px;
    position: sticky;
    top: 0;
    z-index: 20;
    height: 52px;
    padding: 0 8px;
    background: color-mix(in srgb, var(--paper) 92%, transparent);
    backdrop-filter: blur(8px);
    border-bottom: 1px solid var(--rule);
  }
  .mb-title {
    font-weight: 600;
  }
}
</style>
