<script setup lang="ts">
// 更正借阅或归还日期。只能改日期、备注，必须写原因；不能换学生或副本，也不能伪造状态。
import { reactive, ref, watch } from 'vue'
import { LoaderCircle } from 'lucide-vue-next'
import { loans as loanApi, returns as returnApi } from '@/api/endpoints'
import type { LoanView } from '@/api/types'
import { fieldError, fromLocalInput, toLocalInput } from '@/lib/forms'
import Modal from '@/components/Modal.vue'
import FormErrors from '@/components/FormErrors.vue'

const props = defineProps<{ loan: LoanView | null; kind: 'loans' | 'returns' }>()
const emit = defineEmits<{ close: []; saved: [l: LoanView] }>()
const form = reactive({ borrowedAt: '', dueAt: '', returnedAt: '', note: '', reason: '' })
const busy = ref(false)
const error = ref<unknown>(null)

watch(
  () => props.loan,
  (l) => {
    if (!l) return
    error.value = null
    Object.assign(form, {
      borrowedAt: toLocalInput(l.borrowedAt),
      dueAt: toLocalInput(l.dueAt),
      returnedAt: toLocalInput(l.returnedAt),
      note: l.note ?? '',
      reason: '',
    })
  },
)

async function save() {
  if (!props.loan) return
  busy.value = true
  error.value = null
  try {
    const saved =
      props.kind === 'loans'
        ? await loanApi.correct(props.loan.id, {
            borrowedAt: fromLocalInput(form.borrowedAt),
            dueAt: fromLocalInput(form.dueAt),
            returnedAt: props.loan.returnedAt ? fromLocalInput(form.returnedAt) : null,
            note: form.note,
            reason: form.reason.trim(),
          })
        : await returnApi.correct(props.loan.id, { returnedAt: fromLocalInput(form.returnedAt), note: form.note, reason: form.reason.trim() })
    emit('saved', saved)
    emit('close')
  } catch (e) {
    error.value = e
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Modal :open="!!loan" :title="kind === 'loans' ? '更正借阅记录' : '更正归还日期'" width="540px" @close="emit('close')">
    <form v-if="loan" id="corr-form" class="form-grid" novalidate @submit.prevent="save">
      <p class="span-2 ink-2">
        <span class="serif">{{ loan.title }}</span> · {{ loan.studentName }} <span class="mono muted">{{ loan.accessionNo }}</span>
      </p>
      <template v-if="kind === 'loans'">
        <div class="field">
          <label for="cr-b">借出时间</label>
          <input id="cr-b" v-model="form.borrowedAt" class="input" type="datetime-local" :aria-invalid="!!fieldError(error, 'borrowedAt')" />
          <span class="error-text">{{ fieldError(error, 'borrowedAt') }}</span>
        </div>
        <div class="field">
          <label for="cr-d">应还时间</label>
          <input id="cr-d" v-model="form.dueAt" class="input" type="datetime-local" :aria-invalid="!!fieldError(error, 'dueAt')" />
          <span class="error-text">{{ fieldError(error, 'dueAt') }}</span>
        </div>
      </template>
      <div v-if="kind === 'returns' || loan.returnedAt" class="field" :class="{ 'span-2': kind === 'returns' }">
        <label for="cr-r">归还时间</label>
        <input id="cr-r" v-model="form.returnedAt" class="input" type="datetime-local" :aria-invalid="!!fieldError(error, 'returnedAt')" />
        <span class="error-text">{{ fieldError(error, 'returnedAt') }}</span>
      </div>
      <div class="field span-2">
        <label for="cr-n">备注</label>
        <input id="cr-n" v-model="form.note" class="input" maxlength="200" />
      </div>
      <div class="field span-2">
        <label for="cr-reason" class="req">更正原因</label>
        <input id="cr-reason" v-model="form.reason" class="input" maxlength="200" placeholder="例如：柜台登记日期录错" :aria-invalid="!!fieldError(error, 'reason')" />
        <span class="error-text">{{ fieldError(error, 'reason') }}</span>
      </div>
      <p class="span-2 form-note">时间按本机时区显示，保存时换算为 UTC。更正前后的内容都会写入审计日志。</p>
      <div class="span-2"><FormErrors :error="error" /></div>
    </form>
    <template #foot>
      <button class="btn" type="button" @click="emit('close')">取消</button>
      <button class="btn btn-primary" type="submit" form="corr-form" :disabled="busy"><LoaderCircle v-if="busy" class="spin" />保存更正</button>
    </template>
  </Modal>
</template>
