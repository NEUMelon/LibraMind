<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { Pencil, KeyRound, Trash2, ScanLine, LoaderCircle } from 'lucide-vue-next'
import { students as studentApi } from '@/api/endpoints'
import { errorMessage } from '@/api/http'
import type { Eligibility, LoanView, StudentView } from '@/api/types'
import { dueInfo, fmtDate, loanStatus } from '@/lib/format'
import { toast } from '@/stores/toast'
import Modal from '@/components/Modal.vue'
import ErrorNotice from '@/components/ErrorNotice.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import ReasonDialog from '@/components/ReasonDialog.vue'
import StudentFormDialog from './StudentFormDialog.vue'

const props = defineProps<{ id: number | null }>()
const emit = defineEmits<{ close: []; changed: [] }>()

const student = ref<StudentView | null>(null)
const eligibility = ref<Eligibility | null>(null)
const active = ref<LoanView[]>([])
const history = ref<LoanView[]>([])
const historyTotal = ref(0)
const loading = ref(false)
const error = ref<unknown>(null)

async function load(id: number) {
  loading.value = true
  error.value = null
  try {
    const [s, e, a, h] = await Promise.all([
      studentApi.get(id),
      studentApi.eligibility(id),
      studentApi.loans(id, { status: 'ACTIVE', size: 20 }),
      studentApi.history(id, { size: 10 }),
    ])
    student.value = s
    eligibility.value = e
    active.value = a.items.filter((l) => l.status === 'ACTIVE')
    history.value = h.items
    historyTotal.value = h.totalElements
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}
watch(() => props.id, (id) => id && load(id), { immediate: true })

const editing = ref(false)
const removing = ref(false)
const account = ref(false)
const accUser = ref('')
const accPass = ref('')
const accBusy = ref(false)
const accError = ref('')

function openAccount() {
  accUser.value = `student${student.value?.studentNo ?? ''}`
  accPass.value = ''
  accError.value = ''
  account.value = true
}
async function createAccount() {
  if (!student.value) return
  if (accPass.value.length < 8) {
    accError.value = '密码至少 8 位'
    return
  }
  accBusy.value = true
  accError.value = ''
  try {
    await studentApi.createAccount(student.value.id, accUser.value.trim(), accPass.value)
    account.value = false
    accPass.value = ''
    toast('ok', '账号已开通', `用户名 ${accUser.value}，请当面告知初始密码`)
  } catch (e) {
    accError.value = errorMessage(e)
  } finally {
    accBusy.value = false
  }
}

async function remove(reason: string) {
  const result = await studentApi.remove(student.value!.id, reason)
  // 有借阅历史的学生会被停用而不是删除，按响应提示并刷新。
  if (result === 'DISABLED') {
    toast('info', '已停用该学生', '有借阅历史，记录保留')
    await load(student.value!.id)
  } else {
    toast('ok', '已删除学生')
    emit('close')
  }
  emit('changed')
}
const deleteHint = computed(() =>
  active.value.length
    ? `该学生还有 ${active.value.length} 册未还。`
    : historyTotal.value
      ? '该学生有借阅历史，提交后会改为停用，记录保留。'
      : '该学生没有借阅历史，提交后会被删除。',
)
</script>

<template>
  <Modal :open="id !== null" side title="学生" width="600px" @close="emit('close')">
    <SkeletonRows v-if="loading && !student" :rows="5" :cols="3" />
    <ErrorNotice v-else-if="error" :error="error" :retry="() => id && load(id)" />
    <div v-else-if="student" class="sd">
      <header class="sh">
        <div>
          <h3 class="serif nm">{{ student.name }}</h3>
          <p class="mono muted">{{ student.studentNo }}</p>
          <p class="ink-2">{{ student.college }} · {{ student.major }}</p>
        </div>
        <span class="badge" :class="student.enabled ? 'ok' : ''">{{ student.enabled ? '启用' : '已停用' }}</span>
      </header>
      <div class="acts">
        <RouterLink class="btn btn-primary btn-sm" :to="{ path: '/desk', query: { student: student.studentNo } }"><ScanLine />到流通台办理</RouterLink>
        <button class="btn btn-sm" type="button" @click="editing = true"><Pencil />编辑</button>
        <button class="btn btn-sm" type="button" @click="openAccount"><KeyRound />开通账号</button>
        <button class="btn btn-sm btn-danger" type="button" @click="removing = true"><Trash2 />删除 / 停用</button>
      </div>

      <dl v-if="eligibility" class="dl quota">
        <dt>在借</dt><dd class="num">{{ eligibility.currentBorrowedCount }} / {{ eligibility.limit }} 册</dd>
        <dt>逾期</dt><dd class="num" :class="{ danger: eligibility.overdueCount }">{{ eligibility.overdueCount }} 册</dd>
        <dt>可借</dt><dd>{{ eligibility.canBorrow ? `还可借 ${eligibility.remaining} 册` : '当前不能借书' }}</dd>
      </dl>

      <section class="sec">
        <h4>在借</h4>
        <ul v-if="active.length" class="list">
          <li v-for="l in active" :key="l.id">
            <span class="serif truncate">{{ l.title }}</span>
            <span class="mono muted xs">{{ l.accessionNo }}</span>
            <span class="badge" :class="dueInfo(l.dueAt, l.overdue).tone">{{ dueInfo(l.dueAt, l.overdue).label }}</span>
          </li>
        </ul>
        <p v-else class="muted small">没有在借的书。</p>
      </section>

      <section class="sec">
        <h4>借阅历史 <span class="muted num">{{ historyTotal }}</span></h4>
        <table v-if="history.length" class="table">
          <thead><tr><th>书名</th><th>借出</th><th>归还</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="l in history" :key="l.id">
              <td class="serif">{{ l.title }}</td>
              <td class="num small nowrap">{{ fmtDate(l.borrowedAt) }}</td>
              <td class="num small nowrap">{{ fmtDate(l.returnedAt) }}</td>
              <td><span class="badge" :class="loanStatus[l.status].tone">{{ loanStatus[l.status].label }}</span></td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted small">没有借阅历史。</p>
        <RouterLink v-if="historyTotal > history.length" class="small" :to="{ path: '/loans', query: { q: student.studentNo } }">在借书记录中查看全部</RouterLink>
      </section>
    </div>

    <StudentFormDialog :open="editing" :student="student" @close="editing = false" @saved="(s) => { student = s; emit('changed'); toast('ok', '已保存') }" />
    <ReasonDialog :open="removing" title="删除或停用学生" confirm-text="提交" :action="remove" @close="removing = false">
      {{ deleteHint }}
    </ReasonDialog>
    <Modal :open="account" title="开通学生账号" width="440px" @close="account = false">
      <form id="acc-form" class="acc" @submit.prevent="createAccount">
        <p class="muted small">一个学生只能有一个账号。初始密码不会再次显示，请当面告知学生。</p>
        <div class="field">
          <label for="acc-u" class="req">用户名</label>
          <input id="acc-u" v-model="accUser" class="input mono" autocomplete="off" />
        </div>
        <div class="field">
          <label for="acc-p" class="req">初始密码</label>
          <input id="acc-p" v-model="accPass" class="input" type="password" autocomplete="new-password" minlength="8" />
        </div>
        <p v-if="accError" class="error-text" role="alert">{{ accError }}</p>
      </form>
      <template #foot>
        <button class="btn" type="button" @click="account = false">取消</button>
        <button class="btn btn-primary" type="submit" form="acc-form" :disabled="accBusy"><LoaderCircle v-if="accBusy" class="spin" />开通</button>
      </template>
    </Modal>
  </Modal>
</template>

<style scoped>
.sh {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.nm {
  font-size: 24px;
}
.acts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 16px 0 18px;
}
.quota {
  padding: 12px 14px;
  border-radius: var(--r-3);
  background: var(--surface-sunk);
  border: 1px solid var(--rule);
}
.danger {
  color: var(--danger);
}
.sec {
  margin-top: 22px;
}
.sec h4 {
  margin-bottom: 8px;
}
.list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 10px;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--rule);
}
.acc {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
</style>
