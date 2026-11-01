<script setup lang="ts">
// 需要填写原因的操作（作废、撤销、删除/停用）。原因会写入审计日志。
import { ref, watch } from 'vue'
import { LoaderCircle } from 'lucide-vue-next'
import { errorMessage } from '@/api/http'
import Modal from './Modal.vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    confirmText: string
    danger?: boolean
    placeholder?: string
    action: (reason: string) => Promise<unknown>
  }>(),
  { danger: true, placeholder: '写明原因，便于以后核对' },
)
const emit = defineEmits<{ close: []; done: [result: unknown] }>()
const reason = ref('')
const busy = ref(false)
const error = ref('')
watch(
  () => props.open,
  (o) => {
    if (o) {
      reason.value = ''
      error.value = ''
    }
  },
)
async function submit() {
  if (!reason.value.trim()) {
    error.value = '请填写原因'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const result = await props.action(reason.value.trim())
    emit('done', result)
    emit('close')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Modal :open="open" :title="title" width="480px" @close="emit('close')">
    <form id="reason-form" class="rf" @submit.prevent="submit">
      <div class="desc"><slot /></div>
      <div class="field">
        <label for="reason-text" class="req">原因</label>
        <textarea id="reason-text" v-model="reason" class="textarea" maxlength="200" :placeholder="placeholder" :aria-invalid="!!error"></textarea>
        <span v-if="error" class="error-text" role="alert">{{ error }}</span>
      </div>
    </form>
    <template #foot>
      <button class="btn" type="button" @click="emit('close')">取消</button>
      <button class="btn" :class="danger ? 'btn-danger-solid' : 'btn-primary'" type="submit" form="reason-form" :disabled="busy">
        <LoaderCircle v-if="busy" class="spin" />{{ confirmText }}
      </button>
    </template>
  </Modal>
</template>

<style scoped>
.rf {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.desc {
  color: var(--ink-2);
  line-height: 1.7;
}
.desc:empty {
  display: none;
}
</style>
