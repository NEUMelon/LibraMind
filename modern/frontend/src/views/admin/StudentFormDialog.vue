<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { LoaderCircle } from 'lucide-vue-next'
import { students as studentApi } from '@/api/endpoints'
import type { StudentInput, StudentView } from '@/api/types'
import { fieldError } from '@/lib/forms'
import Modal from '@/components/Modal.vue'
import FormErrors from '@/components/FormErrors.vue'

const props = defineProps<{ open: boolean; student: StudentView | null }>()
const emit = defineEmits<{ close: []; saved: [s: StudentView] }>()
const form = reactive<StudentInput>({ studentNo: '', name: '', college: '', major: '', enabled: true })
const busy = ref(false)
const error = ref<unknown>(null)

watch(
  () => props.open,
  (o) => {
    if (!o) return
    error.value = null
    const s = props.student
    Object.assign(form, s ? { studentNo: s.studentNo, name: s.name, college: s.college, major: s.major, enabled: s.enabled } : { studentNo: '', name: '', college: '', major: '', enabled: true })
  },
)

async function submit() {
  busy.value = true
  error.value = null
  try {
    const input = { ...form, studentNo: form.studentNo.trim(), name: form.name.trim(), college: form.college.trim(), major: form.major.trim() }
    const saved = props.student ? await studentApi.update(props.student.id, input) : await studentApi.create(input)
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
  <Modal :open="open" :title="student ? '编辑学生' : '新增学生'" width="520px" @close="emit('close')">
    <form id="student-form" class="form-grid" novalidate @submit.prevent="submit">
      <div class="field">
        <label for="sf-no" class="req">学号</label>
        <input id="sf-no" v-model="form.studentNo" class="input mono" maxlength="32" autocomplete="off" :aria-invalid="!!fieldError(error, 'studentNo')" />
        <span class="error-text">{{ fieldError(error, 'studentNo') }}</span>
      </div>
      <div class="field">
        <label for="sf-name" class="req">姓名</label>
        <input id="sf-name" v-model="form.name" class="input" maxlength="64" autocomplete="off" :aria-invalid="!!fieldError(error, 'name')" />
        <span class="error-text">{{ fieldError(error, 'name') }}</span>
      </div>
      <div class="field">
        <label for="sf-college" class="req">学院</label>
        <input id="sf-college" v-model="form.college" class="input" maxlength="100" :aria-invalid="!!fieldError(error, 'college')" />
        <span class="error-text">{{ fieldError(error, 'college') }}</span>
      </div>
      <div class="field">
        <label for="sf-major" class="req">专业</label>
        <input id="sf-major" v-model="form.major" class="input" maxlength="100" :aria-invalid="!!fieldError(error, 'major')" />
        <span class="error-text">{{ fieldError(error, 'major') }}</span>
      </div>
      <label class="checkbox span-2"><input v-model="form.enabled" type="checkbox" />启用（停用后不能借书，历史记录保留）</label>
      <div class="span-2"><FormErrors :error="error" /></div>
    </form>
    <template #foot>
      <button class="btn" type="button" @click="emit('close')">取消</button>
      <button class="btn btn-primary" type="submit" form="student-form" :disabled="busy"><LoaderCircle v-if="busy" class="spin" />保存</button>
    </template>
  </Modal>
</template>
