<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Eye, EyeOff, LoaderCircle } from 'lucide-vue-next'
import { useSession } from '@/stores/session'
import { ApiError } from '@/api/http'

const session = useSession()
const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const reveal = ref(false)
const busy = ref(false)
const error = ref('')

async function submit() {
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const user = await session.login(username.value.trim(), password.value)
    password.value = ''
    const next = typeof route.query.next === 'string' && route.query.next.startsWith('/') ? route.query.next : ''
    const home = user.role === 'ADMIN' ? '/desk' : '/shelf'
    router.replace(next || home)
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) error.value = '用户名或密码不正确'
    else if (e instanceof ApiError && e.status === 0) error.value = '无法连接服务器，请确认后端已启动'
    else error.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login">
    <div class="wrap">
      <div class="intro">
        <svg class="logo" viewBox="0 0 32 32" aria-hidden="true"><rect width="32" height="32" rx="7" fill="var(--accent)"/><path d="M9 8h4v16H9zM14.5 8h3.5v16h-3.5zM19.5 9.2l3.4-.9 3.6 14.6-3.4.9z" fill="var(--accent-ink)"/></svg>
        <h1 class="serif">LibraMind 图书馆</h1>
        <p>学生查书、借阅与续借；馆员流通、编目与馆藏维护。</p>
      </div>

      <form class="card" novalidate @submit.prevent="submit">
        <div class="card-head">
          <span class="section-title">读者 / 馆员登录</span>
        </div>
        <div class="field">
          <label for="u">用户名</label>
          <input
            id="u"
            v-model="username"
            class="input input-lg"
            autocomplete="username"
            autocapitalize="off"
            spellcheck="false"
            placeholder="学号账号或管理员账号"
            autofocus
          />
        </div>
        <div class="field">
          <label for="p">密码</label>
          <div class="pw">
            <input
              id="p"
              v-model="password"
              class="input input-lg"
              :type="reveal ? 'text' : 'password'"
              autocomplete="current-password"
            />
            <button
              class="btn btn-quiet btn-icon eye"
              type="button"
              :aria-label="reveal ? '隐藏密码' : '显示密码'"
              @click="reveal = !reveal"
            >
              <EyeOff v-if="reveal" /><Eye v-else />
            </button>
          </div>
        </div>
        <p v-if="error" class="err" role="alert">{{ error }}</p>
        <button class="btn btn-primary btn-lg submit" type="submit" :disabled="busy">
          <LoaderCircle v-if="busy" class="spin" />{{ busy ? '正在登录' : '登录' }}
        </button>
        <p class="foot">账号由图书馆开通。忘记密码请到流通台联系馆员。</p>
        <span class="hole" aria-hidden="true"></span>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 24px 16px;
  background: var(--paper);
}
.wrap {
  width: min(400px, 100%);
}
.intro {
  margin-bottom: 22px;
}
.logo {
  width: 36px;
  height: 36px;
  margin-bottom: 18px;
}
h1 {
  font-size: 28px;
  font-weight: 600;
  letter-spacing: 0.02em;
}
.intro p {
  margin-top: 6px;
  color: var(--ink-3);
}
.card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 22px 22px 30px;
  background: var(--surface);
  border: 1px solid var(--rule);
  border-radius: 6px;
  box-shadow: var(--shadow-2);
}
.card::before {
  /* 目录卡片顶部的红线 */
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  top: 46px;
  height: 1px;
  background: color-mix(in srgb, var(--danger) 45%, transparent);
}
.card-head {
  height: 24px;
  margin-bottom: 4px;
}
.pw {
  position: relative;
}
.pw .input {
  padding-right: 44px;
}
.eye {
  position: absolute;
  right: 4px;
  top: 5px;
}
.submit {
  width: 100%;
  margin-top: 4px;
}
.err {
  margin: -4px 0 -4px;
  padding: 8px 10px;
  border-radius: var(--r-2);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: var(--t-sm);
}
.foot {
  font-size: var(--t-xs);
  color: var(--ink-3);
  text-align: center;
}
.hole {
  /* 目录卡底部的穿杆孔 */
  position: absolute;
  bottom: 9px;
  left: 50%;
  width: 14px;
  height: 14px;
  margin-left: -7px;
  border-radius: 50%;
  background: var(--paper);
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.18);
}
.spin {
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
